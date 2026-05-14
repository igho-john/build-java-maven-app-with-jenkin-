#!/usr/bin/env groovy
pipeline {
    agent any
    tools {
        maven 'maven-3.9'
    }
    stages {
        stage("build jar") {
            steps {
                script {
                    echo "building the application ...."
                    sh "mvn clean package"
                }
            }
        }
        stage("build image") {
            steps {
                script {
                    echo "building the docker image ...."
                    withCredentials([usernamePassword(
                        credentialsId: 'docker-hub-repo',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh 'docker build -t ighojohn/demo-app:2.1 .'
                        sh "echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin"
                        sh 'docker push ighojohn/demo-app:2.1'
                    }
                }
            }
        }
        stage("deploy") {
            steps {
                script {
                    echo "deploying the application ...."
                }
            }
        }
    }
}
