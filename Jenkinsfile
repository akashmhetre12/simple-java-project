pipeline {
    agent any

    parameters {
        choice(
            name: 'ENV',
            choices: ['dev', 'uat', 'prod'],
            description: 'Target environment'
        )
        string(
            name: 'BRANCH',
            defaultValue: 'main',
            description: 'Git branch to build'
        )
        booleanParam(
            name: 'TRIGGER_INFRA_APPLY',
            defaultValue: true,
            description: 'Automatically trigger infra pipeline apply with the new AMI'
        )
    }

    environment {
        AWS_REGION           = 'ap-south-1'
        NEXUS_URL            = 'http://<nexus-instance-ip>:8081'
        NEXUS_REPO           = 'maven-releases'
        SONARQUBE_ENV        = 'MySonarQubeServer'   // Name configured in Jenkins > Manage Jenkins > System > SonarQube servers
        TEMPLATE_INSTANCE_TAG = "app-template-${params.ENV}"
        APP_NAME             = 'myapp'
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scmGit(
                    branches: [[name: "*/${params.BRANCH}"]],
                    userRemoteConfigs: [[url: 'https://github.com/akashmhetre12/myapp-repo.git']]
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
                    sh 'mvn -B sonar:sonar -Dsonar.projectKey=${APP_NAME}-${ENV}'
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
                    sh """
                        mvn -B deploy:deploy-file \
                        -Durl=${NEXUS_URL}/repository/${NEXUS_REPO}/ \
                        -DrepositoryId=nexus \
                        -Dfile=target/myapp.jar \
                        -DgroupId=com.myapp \
                        -DartifactId=myapp \
                        -Dversion=${BUILD_NUMBER} \
                        -Dpackaging=jar \
                        -Dusername=${NEXUS_USER} \
                        -Dpassword=${NEXUS_PASS}
                    """
                }
            }
        }

        stage('Resolve Template Instance') {
            steps {
                script {
                    env.TEMPLATE_INSTANCE_ID = sh(
                        script: """
                            aws ec2 describe-instances \
                              --region ${AWS_REGION} \
                              --filters "Name=tag:Name,Values=${TEMPLATE_INSTANCE_TAG}" "Name=instance-state-name,Values=running" \
                              --query "Reservations[0].Instances[0].InstanceId" \
                              --output text
                        """,
                        returnStdout: true
                    ).trim()

                    env.TEMPLATE_INSTANCE_IP = sh(
                        script: """
                            aws ec2 describe-instances \
                              --region ${AWS_REGION} \
                              --instance-ids ${env.TEMPLATE_INSTANCE_ID} \
                              --query "Reservations[0].Instances[0].PrivateIpAddress" \
                              --output text
                        """,
                        returnStdout: true
                    ).trim()

                    if (!env.TEMPLATE_INSTANCE_ID?.trim() || env.TEMPLATE_INSTANCE_ID == 'None') {
                        error "Could not resolve template instance for tag ${TEMPLATE_INSTANCE_TAG}"
                    }
                    echo "Deploying to template instance ${env.TEMPLATE_INSTANCE_ID} (${env.TEMPLATE_INSTANCE_IP})"
                }
            }
        }

        stage('Deploy via SSH') {
            steps {
                sshagent(credentials: ['app-deploy-ssh-key']) {
                    sh """
                        scp -o StrictHostKeyChecking=no target/myapp.jar ec2-user@${env.TEMPLATE_INSTANCE_IP}:/tmp/myapp.jar

                        ssh -o StrictHostKeyChecking=no ec2-user@${env.TEMPLATE_INSTANCE_IP} '
                            sudo systemctl stop myapp || true
                            sudo mv /tmp/myapp.jar /opt/myapp/myapp.jar
                            sudo systemctl start myapp
                            sleep 5
                            curl -f http://localhost:8080/health
                        '
                    """
                }
            }
        }

        stage('Bake AMI') {
            steps {
                script {
                    def amiName = "${APP_NAME}-${params.ENV}-${env.BUILD_NUMBER}"

                    env.NEW_AMI_ID = sh(
                        script: """
                            aws ec2 create-image \
                              --region ${AWS_REGION} \
                              --instance-id ${env.TEMPLATE_INSTANCE_ID} \
                              --name "${amiName}" \
                              --description "Built from Jenkins build #${env.BUILD_NUMBER}, branch ${params.BRANCH}" \
                              --no-reboot \
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

        stage('Trigger Infra Apply') {
            when {
                expression { params.TRIGGER_INFRA_APPLY }
            }
            steps {
                build job: 'infra-pipeline',
                    parameters: [
                        string(name: 'ENV', value: params.ENV),
                        string(name: 'ACTION', value: 'apply'),
                        string(name: 'BRANCH', value: 'main'),
                        string(name: 'AMI_ID', value: env.NEW_AMI_ID)
                    ],
                    wait: true
            }
        }
    }

    post {
        success {
            echo "Pipeline succeeded. New AMI ${env.NEW_AMI_ID} deployed to ${params.ENV} ASG (if triggered)."
        }
        failure {
            echo "Pipeline failed. Check the stage logs above."
        }
    }
}