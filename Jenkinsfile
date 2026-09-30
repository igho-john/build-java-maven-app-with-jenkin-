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
                    def dockerCmd = 'docker run -p 80:3000 -d ighojohn/testing-app:23'
                    sshagent(['ec2-server-key']) {
                        sh "ssh -o StrictHostKeyChecking=no ec2-user@99.79.70.217 '${dockerCmd}'"
                    }
                }
            }
        }
    }
}
