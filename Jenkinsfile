pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        timestamps()
    }

    parameters {
        choice(
            name: 'ENVIRONMENT',
            choices: ['dev', 'uat', 'prod'],
            description: 'Target environment'
        )
        string(
            name: 'BRANCH',
            defaultValue: 'main',
            description: 'Git branch to build'
        )
        booleanParam(
            name: 'TRIGGER_ASG_REFRESH',
            defaultValue: true,
            description: 'Update the Launch Template to the new AMI and roll it out via ASG instance refresh'
        )
        string(
            name: 'BASE_AMI_ID',
            defaultValue: 'ami-0748e05f6f49a339a',
            description: 'Base OS AMI (Java + systemd unit pre-baked) to launch the builder instance from'
        )
        string(
            name: 'SUBNET_ID',
            defaultValue: 'subnet-026855d0b709e4f47',
            description: 'Public subnet to launch the temporary builder instance in'
        )
        string(
            name: 'SECURITY_GROUP_ID',
            defaultValue: 'sg-0d5f36e68410bb205',
            description: 'Security group for the builder instance (must allow SSH from Jenkins)'
        )
        string(
            name: 'INSTANCE_TYPE',
            defaultValue: 't3.micro',
            description: 'Instance type for the temporary builder instance'
        )
        string(
            name: 'KEY_NAME',
            defaultValue: 'Mumbai',
            description: 'EC2 key pair name (must match the private key in the app-deploy-ssh-key credential)'
        )
    }

    environment {
        AWS_REGION     = 'ap-south-1'
        NEXUS_URL      = 'http://10.0.2.207:8081'
        NEXUS_REPO     = 'maven-releases'   // confirm this matches the repo name in Nexus
        SONARQUBE_ENV  = 'MySonarQubeServer'
        APP_NAME       = 'simple-java-project'
        JAR_FILE       = 'target/simple-java-app-1.0.0.jar'
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scmGit(
                    branches: [[name: "*/${params.BRANCH}"]],
                    userRemoteConfigs: [[url: 'https://github.com/akashmhetre12/simple-java-project.git']]
                )
            }
        }

        stage('Build') {
            steps {
                sh 'mvn -B clean compile'
            }
        }

        stage('Unit Tests') {
            steps {
                sh 'mvn -B test'
            }
            post {
                always {
                    junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv("${SONARQUBE_ENV}") {
                    sh "mvn -B org.sonarsource.scanner.maven:sonar-maven-plugin:5.7.0.6970:sonar -Dsonar.projectKey=${APP_NAME}-${params.ENVIRONMENT}"
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Package') {
            steps {
                sh 'mvn -B package -DskipTests'
            }
        }

        stage('Upload to Nexus') {
             steps {
                withCredentials([usernamePassword(
                credentialsId: 'nexus-credentials',
                usernameVariable: 'NEXUS_USER',
                passwordVariable: 'NEXUS_PASS'
                )]) {
                    writeFile file: 'nexus-settings.xml', text: """
                        <settings>
                        <servers>
                        <server>
                            <id>nexus</id>
                            <username>${NEXUS_USER}</username>
                             <password>${NEXUS_PASS}</password>
                        </server>
                    </servers>
                    </settings>
            """
            sh """
                mvn -B -s nexus-settings.xml deploy:deploy-file \
                -Durl=${NEXUS_URL}/repository/${NEXUS_REPO}/ \
                -DrepositoryId=nexus \
                -Dfile=${JAR_FILE} \
                -DgroupId=com.myapp \
                -DartifactId=simple-java-app \
                -Dversion=${BUILD_NUMBER} \
                -Dpackaging=jar
            """
            sh 'rm -f nexus-settings.xml'
        }
    }
}

        stage('Launch Builder Instance') {
            steps {
                script {
                    if (!params.BASE_AMI_ID?.trim() || !params.SUBNET_ID?.trim() || !params.SECURITY_GROUP_ID?.trim() || !params.KEY_NAME?.trim()) {
                        error "BASE_AMI_ID, SUBNET_ID, SECURITY_GROUP_ID and KEY_NAME are all required"
                    }

                    def tagName = "app-builder-${params.ENVIRONMENT}-${env.BUILD_NUMBER}"

                    env.BUILDER_INSTANCE_ID = sh(
                        script: """
                            aws ec2 run-instances \
                              --region ${AWS_REGION} \
                              --image-id ${params.BASE_AMI_ID} \
                              --instance-type ${params.INSTANCE_TYPE} \
                              --subnet-id ${params.SUBNET_ID} \
                              --security-group-ids ${params.SECURITY_GROUP_ID} \
                              --key-name ${params.KEY_NAME} \
                              --associate-public-ip-address \
                              --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${tagName}},{Key=Purpose,Value=ami-builder},{Key=Environment,Value=${params.ENVIRONMENT}}]" \
                              --query "Instances[0].InstanceId" \
                              --output text
                        """,
                        returnStdout: true
                    ).trim()

                    echo "Launched builder instance ${env.BUILDER_INSTANCE_ID}"

                    sh """
                        aws ec2 wait instance-running \
                          --region ${AWS_REGION} \
                          --instance-ids ${env.BUILDER_INSTANCE_ID}
                    """

                    // Public IP can take a few seconds to show up after the instance
                    // reaches "running", so poll for it instead of reading it once.
                    env.BUILDER_INSTANCE_IP = sh(
                        script: """
                            for i in \$(seq 1 12); do
                                IP=\$(aws ec2 describe-instances \
                                  --region ${AWS_REGION} \
                                  --instance-ids ${env.BUILDER_INSTANCE_ID} \
                                  --query "Reservations[0].Instances[0].PublicIpAddress" \
                                  --output text)
                                if [ "\$IP" != "None" ] && [ -n "\$IP" ]; then
                                    echo "\$IP"
                                    exit 0
                                fi
                                sleep 5
                            done
                            echo "No public IP assigned" >&2
                            exit 1
                        """,
                        returnStdout: true
                    ).trim()

                    echo "Builder instance public IP: ${env.BUILDER_INSTANCE_IP}"
                }
            }
        }

        stage('Wait for SSH Ready') {
            steps {
                sshagent(credentials: ['app-deploy-ssh-key']) {
                    timeout(time: 3, unit: 'MINUTES') {
                        sh """
                            until ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 ubuntu@${env.BUILDER_INSTANCE_IP} 'echo ready'; do
                                echo "Waiting for SSH on ${env.BUILDER_INSTANCE_IP}..."
                                sleep 5
                            done
                        """
                    }
                }
            }
        }

        stage('Deploy via SSH') {
            steps {
                sshagent(credentials: ['app-deploy-ssh-key']) {
                    sh """
                    scp -o StrictHostKeyChecking=no ${JAR_FILE} ubuntu@${env.BUILDER_INSTANCE_IP}:/tmp/simple-java-app-1.0.0.jar

                    ssh -o StrictHostKeyChecking=no ubuntu@${env.BUILDER_INSTANCE_IP} '
                    sudo systemctl stop simple-java-app || true
                    sudo mv /tmp/simple-java-app-1.0.0.jar /opt/simple-java-app/simple-java-app-1.0.0.jar
                    sudo systemctl enable simple-java-app
                    sudo systemctl start simple-java-app
                    sleep 15
                    curl http://${env.BUILDER_INSTANCE_IP}:8081
                    echo "${env.BUILDER_INSTANCE_IP}"
                    curl -f http://localhost:8080/health || curl -f http://localhost:8081/
                    '
                    """
                }
            }
        }

      stage('Stop App Before Imaging') {
    steps {
        sshagent(credentials: ['app-deploy-ssh-key']) {
            sh """
                ssh -o StrictHostKeyChecking=no ubuntu@${env.BUILDER_INSTANCE_IP} '
                sudo systemctl stop simple-java-app
                echo "--- verifying jar before imaging ---"
                ls -la /opt/simple-java-app/
                md5sum /opt/simple-java-app/simple-java-app-1.0.0.jar
                df -h /opt
                mount | grep /opt
                sync
                sync
                '
            """
        }
    }
}
 
        stage('Bake AMI') {
            steps {
                script {
                    def amiName = "${APP_NAME}-${params.ENVIRONMENT}-${env.BUILD_NUMBER}"
 
                    env.NEW_AMI_ID = sh(
                        script: """
                            aws ec2 create-image \
                              --region ${AWS_REGION} \
                              --instance-id ${env.BUILDER_INSTANCE_ID} \
                              --name "${amiName}" \
                              --description "Built from Jenkins build #${env.BUILD_NUMBER}, branch ${params.BRANCH}" \
                              --tag-specifications "ResourceType=image,Tags=[{Key=Name,Value=${amiName}},{Key=Environment,Value=${params.ENVIRONMENT}},{Key=BuildNumber,Value=${env.BUILD_NUMBER}}]" \
                              --query "ImageId" \
                              --output text
                        """,
                        returnStdout: true
                    ).trim()
 
                    echo "Created AMI: ${env.NEW_AMI_ID}"
                }
            }
        }

        

        stage('Wait for AMI Available') {
            steps {
                sh """
                    aws ec2 wait image-available \
                      --region ${AWS_REGION} \
                      --image-ids ${env.NEW_AMI_ID}
                """
            }
        }

        stage('Find Target ASG') {
            when {
                expression { params.TRIGGER_ASG_REFRESH }
            }
            steps {
                script {
                    env.ASG_NAME = sh(
                        script: """
                            aws autoscaling describe-auto-scaling-groups \
                              --region ${AWS_REGION} \
                              --query "AutoScalingGroups[?Tags[?Key=='Instance_refresh' && Value=='True'] && Tags[?Key=='Environment' && Value=='${params.ENVIRONMENT}']].AutoScalingGroupName | [0]" \
                              --output text
                        """,
                        returnStdout: true
                    ).trim()

                    if (!env.ASG_NAME?.trim() || env.ASG_NAME == 'None') {
                        error "No ASG found tagged Instance_refresh=True and Environment=${params.ENVIRONMENT}"
                    }

                    env.LAUNCH_TEMPLATE_ID = sh(
                        script: """
                            aws autoscaling describe-auto-scaling-groups \
                              --region ${AWS_REGION} \
                              --auto-scaling-group-names ${env.ASG_NAME} \
                              --query "AutoScalingGroups[0].LaunchTemplate.LaunchTemplateId" \
                              --output text
                        """,
                        returnStdout: true
                    ).trim()

                    if (!env.LAUNCH_TEMPLATE_ID?.trim() || env.LAUNCH_TEMPLATE_ID == 'None') {
                        error "ASG ${env.ASG_NAME} has no LaunchTemplate attached (check for MixedInstancesPolicy instead)"
                    }

                    echo "Target ASG: ${env.ASG_NAME}, Launch Template: ${env.LAUNCH_TEMPLATE_ID}"
                }
            }
        }

        stage('Update Launch Template') {
            when {
                expression { params.TRIGGER_ASG_REFRESH }
            }
            steps {
                script {
                    env.NEW_LT_VERSION = sh(
                        script: """
                            aws ec2 create-launch-template-version \
                              --region ${AWS_REGION} \
                              --launch-template-id ${env.LAUNCH_TEMPLATE_ID} \
                              --source-version '\$Latest' \
                              --launch-template-data '{"ImageId":"${env.NEW_AMI_ID}"}' \
                              --query "LaunchTemplateVersion.VersionNumber" \
                              --output text
                        """,
                        returnStdout: true
                    ).trim()

                    sh """
                        aws ec2 modify-launch-template \
                          --region ${AWS_REGION} \
                          --launch-template-id ${env.LAUNCH_TEMPLATE_ID} \
                          --default-version ${env.NEW_LT_VERSION}
                    """

                    echo "Launch Template ${env.LAUNCH_TEMPLATE_ID} default version set to ${env.NEW_LT_VERSION} (AMI ${env.NEW_AMI_ID})"
                }
            }
        }

        stage('Trigger ASG Instance Refresh') {
            when {
                expression { params.TRIGGER_ASG_REFRESH }
            }
            steps {
                script {
                    env.REFRESH_ID = sh(
                        script: """
                            aws autoscaling start-instance-refresh \
                              --region ${AWS_REGION} \
                              --auto-scaling-group-name ${env.ASG_NAME} \
                              --preferences '{"MinHealthyPercentage":90,"InstanceWarmup":120}' \
                              --query "InstanceRefreshId" \
                              --output text
                        """,
                        returnStdout: true
                    ).trim()

                    echo "Started instance refresh ${env.REFRESH_ID} on ${env.ASG_NAME}"
                }
            }
        }

        stage('Terminate Builder Instance') {
    steps {
        script {
            if (env.BUILDER_INSTANCE_ID?.trim()) {
                echo "Terminating builder instance ${env.BUILDER_INSTANCE_ID}"
                sh """
                    aws ec2 terminate-instances \
                      --region ${AWS_REGION} \
                      --instance-ids ${env.BUILDER_INSTANCE_ID} || true
                """
                sh """
                    aws ec2 wait instance-terminated \
                      --region ${AWS_REGION} \
                      --instance-ids ${env.BUILDER_INSTANCE_ID} || true
                """
            } else {
                echo "No builder instance ID found, skipping termination."
            }
        }
    }
}

stage('Wait for Instance Refresh') {
    when {
        expression { params.TRIGGER_ASG_REFRESH }
    }
    steps {
        timeout(time: 30, unit: 'MINUTES') {
            script {
                def status = ''
                while (!(status in ['Successful', 'Failed', 'Cancelled'])) {
                    status = sh(
                        script: """
                            aws autoscaling describe-instance-refreshes \
                              --region ${AWS_REGION} \
                              --auto-scaling-group-name ${env.ASG_NAME} \
                              --instance-refresh-ids ${env.REFRESH_ID} \
                              --query "InstanceRefreshes[0].Status" \
                              --output text
                        """,
                        returnStdout: true
                    ).trim()
                    echo "${new Date()} - Instance refresh status: ${status}"
                    if (!(status in ['Successful', 'Failed', 'Cancelled'])) {
                        sleep 15
                    }
                }
                if (status != 'Successful') {
                    error "Instance refresh ended with status: ${status}"
                }
            }
        }
    }
}
    } // end stages

    post {
        always {
            script {
                if (env.BUILDER_INSTANCE_ID?.trim()) {
                    echo "Post-block safety check: ensuring builder instance ${env.BUILDER_INSTANCE_ID} is terminated"
                    sh """
                        aws ec2 terminate-instances \
                          --region ${AWS_REGION} \
                          --instance-ids ${env.BUILDER_INSTANCE_ID} || true
                    """
                }
            }
        }
        success {
            echo "Pipeline succeeded. AMI ${env.NEW_AMI_ID} baked" + (params.TRIGGER_ASG_REFRESH ? " and rolled out to ASG ${env.ASG_NAME} via instance refresh ${env.REFRESH_ID}." : ".")
        }
        failure {
            echo "Pipeline failed. Check the stage logs above."
        }
    }
}

       
