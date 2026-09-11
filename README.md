# Simple Java App — CI/CD Pipeline

Jenkins pipeline that builds a Spring Boot app, scans it with SonarQube, publishes
the jar to Nexus, bakes an immutable AMI from a temporary EC2 builder instance,
and rolls the new AMI out across an Auto Scaling Group via instance refresh.

## Architecture

```
Checkout → Build → Test → SonarQube → Quality Gate → Package → Upload to Nexus
    → Launch temp builder EC2 (from base AMI) → Deploy jar via SSH → Verify
    → Stop app, sync disk → Bake AMI → Terminate builder
    → Find ASG (by tag) → Update Launch Template → Trigger ASG Instance Refresh
```

The builder instance is **ephemeral** — launched fresh from the base AMI on every
build, imaged, then terminated. Nothing is deployed by SSH-ing into live ASG
instances; the ASG only ever receives new instances launched from the new AMI.

## Prerequisites

1. **Infra already running**: Nexus, SonarQube, an ASG behind an ALB, target
   group listening on port `8081` (the app's port), Launch Template attached to
   the ASG.
2. **Base AMI**: Java 21 installed, systemd unit created but **not enabled or
   started** (the pipeline enables/starts it after deploying the jar). See
   [Base AMI setup](#base-ami-setup) below.
3. **ASG tagged for discovery** — the pipeline finds its target ASG by tags, not
   by a hardcoded name:
   - `Instance_refresh = True`
   - `Environment = dev` / `uat` / `prod` (must match the `ENVIRONMENT` pipeline
     parameter exactly)
4. **Jenkins credentials configured**:
   - `nexus-credentials` — Username/Password credential for Nexus
   - `app-deploy-ssh-key` — SSH private key matching the `KEY_NAME` EC2 key pair
   - A Secret Text credential holding the SonarQube token, attached to the
     SonarQube server entry in Jenkins global config (see below)

## Base AMI setup

On the instance you'll snapshot as the base AMI:

```bash
sudo tee /etc/systemd/system/simple-java-app.service > /dev/null <<'EOF'
[Unit]
Description=Simple Java App
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/simple-java-app
ExecStart=/usr/bin/java -jar /opt/simple-java-app/simple-java-app-1.0.0.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo mkdir -p /opt/simple-java-app
# Don't enable or start — the pipeline does this after the jar is deployed
```

> The path is `/opt/simple-java-app/`, not `/opt/myapp/` — must match exactly
> what the Jenkinsfile's `Deploy via SSH` stage writes to.

## Nexus setup

1. **Settings → Repository → Repositories → Create repository → maven2 (hosted)**
   - Name: `maven-releases` (must exactly match `NEXUS_REPO` in the Jenkinsfile)
   - Version policy: `Release`
   - Deployment policy: `Allow redeploy` (dev/uat) or `Disable redeploy` (prod,
     once you want stricter hygiene)
2. **Accept the EULA** — fresh Nexus installs block all deploy/write requests
   with a `403 Forbidden` until the EULA is accepted, even for admin. Reads work
   fine, so this is easy to miss until you try to actually publish an artifact.
   Do it via the onboarding wizard on first login, or via the REST API:
   ```bash
   curl -s -u admin:<password> -H "accept: application/json" \
     "http://<nexus-ip>:8081/service/rest/v1/system/eula" \
     | sed 's/"accepted": false/"accepted": true/' > eula.json
   curl -X POST -u admin:<password> \
     -H "Content-Type: application/json; charset=UTF-8" \
     -d @eula.json "http://<nexus-ip>:8081/service/rest/v1/system/eula"
   ```
3. **Deploy user permissions** — whatever user is stored in the
   `nexus-credentials` Jenkins credential needs a role with
   `nx-repository-view-maven2-maven-releases-*` (add + edit), not just read.
4. **Maven auth note**: `maven-deploy-plugin` 3.x no longer accepts
   `-Dusername`/`-Dpassword` on the command line — it only reads credentials
   from a `settings.xml` `<server>` block matching `-DrepositoryId`. The
   Jenkinsfile handles this by writing a temporary `nexus-settings.xml` inside
   the `Upload to Nexus` stage and passing it via `-s`.

## SonarQube setup

1. **Install the SonarQube Scanner plugin** in Jenkins if not already present
   (Manage Jenkins → Plugins).
2. **Generate a token**: SonarQube → avatar → My Account → Security → Generate
   Tokens. Use a Global or Project Analysis Token.
3. **Register the server in Jenkins**: Manage Jenkins → System → SonarQube
   servers → Add SonarQube.
   - Name must exactly match `SONARQUBE_ENV` in the Jenkinsfile
     (`MySonarQubeServer`)
   - Server URL: `http://<sonarqube-ip>:9000`
   - Server authentication token: add a **Secret Text** credential with the
     token from step 2, select it here
4. **Webhook (required)** — `waitForQualityGate` needs this or every build will
   hang for the full timeout instead of returning as soon as analysis
   completes: SonarQube → Administration → Configuration → Webhooks → Create,
   URL `http://<jenkins-ip>:8080/sonarqube-webhook/`
5. **Plugin invocation note**: the Jenkinsfile calls the scanner via its full
   Maven coordinates, not the `sonar:sonar` shorthand:
   ```
   org.sonarsource.scanner.maven:sonar-maven-plugin:5.7.0.6970:sonar
   ```
   The short prefix form fails with *"No plugin found for prefix 'sonar'"*
   unless `settings.xml` has a matching `pluginGroups` entry — using the full
   coordinates avoids that dependency entirely. Also note `sonar.token` is the
   current property name; the older `sonar.login` is deprecated.
6. Projects (`simple-java-project-dev`, `-uat`, `-prod`) are auto-created on
   first analysis — no manual setup needed unless you want a custom Quality
   Gate assigned per project.

## Jenkinsfile parameters

| Parameter | Default | Notes |
|---|---|---|
| `ENVIRONMENT` | — | `dev` / `uat` / `prod`; must match the ASG's `Environment` tag |
| `BRANCH` | `main` | Git branch to build |
| `TRIGGER_ASG_REFRESH` | `true` | If false, only bakes the AMI without rolling it out |
| `BASE_AMI_ID` | *(set)* | Base OS AMI with Java + systemd unit pre-baked |
| `SUBNET_ID` | *(set)* | Public subnet for the temporary builder instance |
| `SECURITY_GROUP_ID` | *(set)* | Must allow inbound SSH (22) from Jenkins |
| `INSTANCE_TYPE` | `t3.micro` | Builder instance size |
| `KEY_NAME` | `Mumbai` | EC2 key pair; must match the private key in `app-deploy-ssh-key` |

`environment {}` block values to confirm match your actual infra:

| Variable | Value | Notes |
|---|---|---|
| `AWS_REGION` | `ap-south-1` | |
| `NEXUS_URL` | `http://13.127.6.25:8081` | |
| `NEXUS_REPO` | `maven-releases` | Must exist in Nexus exactly as named |
| `SONARQUBE_ENV` | `MySonarQubeServer` | Must match the Jenkins SonarQube server name exactly |
| `APP_NAME` | `simple-java-project` | Used for AMI naming and Sonar project keys |

## Rollout mechanics

The pipeline does **not** call a separate infra job. After baking the AMI it:

1. Looks up the ASG tagged `Instance_refresh=True` + `Environment=<ENVIRONMENT>`
2. Reads that ASG's attached Launch Template ID
3. Creates a new Launch Template version pointing at the new AMI, sets it default
4. Calls `start-instance-refresh` (`MinHealthyPercentage: 90`, `InstanceWarmup: 120s`)
5. Polls until the refresh reports `Successful`/`Failed`/`Cancelled`, failing the
   build on anything but success

**Sanity check after any refresh**: if `describe-instance-refreshes` reports
`Successful` in well under a minute, the ASG likely had zero running instances
at the time (nothing to actually replace) rather than a genuinely fast rollout.
Confirm with:
```bash
aws autoscaling describe-auto-scaling-groups \
  --auto-scaling-group-names <asg-name> \
  --query "AutoScalingGroups[0].[DesiredCapacity,MinSize,MaxSize,Instances]"
```

## AMI baking notes

- The builder instance is stopped and explicitly `sync`'d before imaging, and
  `create-image` runs **without** `--no-reboot` — this guarantees the
  filesystem is flushed and consistent before the snapshot. Skipping the
  reboot risks the AMI missing recently-written files (e.g. the deployed jar
  showing up on the live instance but not in the baked image) if buffered
  writes haven't hit disk yet.