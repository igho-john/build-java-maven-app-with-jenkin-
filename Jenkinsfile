#!/usr/bin/env groovy
pipeline {
    agent any
    stages {
        stage('test') {
            steps {
                script {
                    echo "Testing the application..."
                }
            }
        }
        stage('build') {
            steps {
                script {
                    echo "Building the application..."
                }
            }
        }
        stage('deploy') {
            steps {
                script {
                    sshagent(['ec2-server-key']) {
                        sh "scp -o StrictHostKeyChecking=no docker-compose.yml ec2-user@99.79.70.217:/home/ec2-user/docker-compose.yml"
                        sh "scp -o StrictHostKeyChecking=no deploy.sh ec2-user@99.79.70.217:/home/ec2-user/deploy.sh"
                        sh "ssh -o StrictHostKeyChecking=no ec2-user@99.79.70.217 'chmod +x /home/ec2-user/deploy.sh && /home/ec2-user/deploy.sh 23'"
                    }
                }
            }
        }
    }
}