Project Overview
This diagram illustrates a complete Jenkins CI/CD pipeline for a Java Maven Spring Boot application. The pipeline automates the entire software delivery process from the developer writing code to the application running on an AWS EC2 production server. Every git push to the GitHub repository automatically triggers the pipeline — no manual steps required.

![Alt text](/jenkins-java-maven-pipeline.svg)

What This Pipeline Does
•	Automatically detects every code change pushed to GitHub via webhook
•	Checks out the source code from the jenkins-jobs branch
•	Runs all unit tests using Maven — fails fast if any test fails
•	Builds a JAR artifact using Maven package
•	Builds a Docker image using the Dockerfile
•	Pushes the Docker image to both Nexus (self-hosted) and Docker Hub (cloud)
•	Deploys the application to the dev server via SSH and docker compose
•	Notifies on success or failure

Repository
Component / Step	Description
GitHub repo	github.com/igho-john/build-java-maven-app-with-jenkin-
Branch	jenkins-jobs
Docker Hub	hub.docker.com/u/ighojohn — image: ighojohn/demo-app:2.1
Nexus registry	nexus:8083/java-app:1.0 — self-hosted on EC2
 
Diagram — Section by Section

Section 1 — Developer (Local Mac)
The developer writes the Java application locally and maintains four key files in the repository:

Component / Step	Description
pom.xml	Maven build configuration — dependencies, plugins, distributionManagement for Nexus
Dockerfile	Blueprint to build the Docker image — FROM eclipse-temurin:17-jre-alpine
Jenkinsfile	Pipeline definition — all 8 stages defined here in Groovy DSL
.gitignore	Excludes secrets, credentials, build output from version control

💡 Never commit credentials to Git — use Jenkins credentials store and .gitignore

Section 2 — GitHub Repository
The GitHub repository is the single source of truth for all code. It stores all application files and the Jenkinsfile that defines the pipeline. A GitHub webhook is configured to notify Jenkins every time code is pushed.

Component / Step	Description
Repository URL	https://github.com/igho-john/build-java-maven-app-with-jenkin-
Branch	jenkins-jobs — the active development branch
Credential ID	git-credentials — stored securely in Jenkins credentials store
Webhook	GitHub → Settings → Webhooks → http://jenkins-ip:8080/github-webhook/

Section 3 — AWS EC2 Server (Jenkins + Nexus)
Both Jenkins and Nexus run as Docker containers on the same AWS EC2 server. They are connected via a Docker bridge network called jenkins-nexus-network so Jenkins can push images to Nexus using the container name.

Jenkins Container
Component / Step	Description
Image	jenkins/jenkins:lts — official Long Term Support image
Port	8080 — Jenkins UI accessible at http://ec2-ip:8080
Port	50000 — Jenkins agent/build node communication port
Volume 1	jenkins_home:/var/jenkins_home — persists all Jenkins data
Volume 2	/var/run/docker.sock:/var/run/docker.sock — Docker API socket
Volume 3	$(which docker):/usr/bin/docker — Docker binary for running commands

docker run -d \
  --name jenkins \
  --net jenkins-nexus-network \
  -p 8080:8080 -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v $(which docker):/usr/bin/docker \
  jenkins/jenkins:lts

Nexus Container
Component / Step	Description
Image	sonatype/nexus3 — pulled from Docker Hub
Port 8081	Nexus UI — browser interface at http://ec2-ip:8081
Port 8082	Nexus UI alternate (when 8081 conflicts with Mongo Express)
Port 8083	Docker hosted registry — docker push/pull endpoint
Volume	nexus-data:/nexus-data — persists all Nexus data and images
Network	jenkins-nexus-network — Jenkins pushes to nexus:8083 by container name

docker run -d \
  --name nexus \
  --net jenkins-nexus-network \
  -p 8081:8081 -p 8083:8083 \
  -v nexus-data:/nexus-data \
  sonatype/nexus3

💡 Add Nexus to insecure registries in /etc/docker/daemon.json so Docker trusts the HTTP endpoint
{
  "insecure-registries": ["nexus:8083", "your-ec2-ip:8083"]
}

 
Pipeline Stages — Detailed
The Jenkinsfile defines 8 stages that execute in sequence. If any stage fails, all subsequent stages are skipped and Jenkins marks the build as failed.

Stage ① — Checkout
Jenkins clones the repository from GitHub using the stored git-credentials. This is automatic in a declarative pipeline using 'checkout scm'.
stage('Checkout') {
  steps { checkout scm }
}

Stage ② — Test
Maven runs all unit tests. If any test fails the pipeline stops immediately — fail fast principle. JUnit reports are generated for Jenkins to display.
stage('Test') {
  steps { sh 'mvn test' }
}

Stage ③ — Build JAR
Maven compiles the Java source code and packages it into an executable JAR file. The output is stored in the target/ directory inside the Jenkins workspace.
stage('Build JAR') {
  steps { sh 'mvn clean package' }
}
Output: target/java-maven-app-1.1.0-SNAPSHOT.jar

Stage ④ — Docker Build Image
Jenkins builds a Docker image using the Dockerfile in the repository root. The Dockerfile copies the JAR file into the image and sets the startup command.
stage('Build Image') {
  steps { sh 'docker build -t java-maven-app:2.1 .' }
}

Dockerfile:
FROM eclipse-temurin:17-jre-alpine
EXPOSE 8080
COPY ./target/java-maven-app-*.jar /usr/app/
WORKDIR /usr/app
CMD java -jar java-maven-app-*.jar

Stage ⑤ — Push to Nexus
Jenkins authenticates to the Nexus Docker registry using the nexus-credentials stored in Jenkins and pushes the image. Nexus and Jenkins are on the same Docker network so the hostname 'nexus' resolves automatically.
withCredentials([usernamePassword(
  credentialsId: 'nexus-credentials',
  usernameVariable: 'USER',
  passwordVariable: 'PASS'
)]) {
  sh 'docker login nexus:8083 -u $USER -p $PASS'
  sh 'docker tag java-maven-app:2.1 nexus:8083/java-app:2.1'
  sh 'docker push nexus:8083/java-app:2.1'
}

Stage ⑥ — Push to Docker Hub
Jenkins also pushes the image to Docker Hub as a cloud backup registry. The docker-hub-repo credential is used for authentication.
withCredentials([usernamePassword(
  credentialsId: 'docker-hub-repo',
  usernameVariable: 'DOCKER_USER',
  passwordVariable: 'DOCKER_PASS'
)]) {
  sh 'echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin'
  sh 'docker push ighojohn/demo-app:2.1'
}

Stage ⑦ — Deploy
Jenkins SSHs into the dev server and runs docker compose up -d to pull the latest image and restart the application containers.
stage('Deploy') {
  steps { sh 'docker compose up -d' }
}

Stage ⑧ — Post Build Notify
After all stages complete Jenkins sends a notification regardless of outcome.
post {
  success { echo 'Build and push succeeded! ✅' }
  failure { echo 'Pipeline failed! Check logs ❌' }
  always  { echo 'Cleaning up workspace...' }
}
 
Jenkins Credentials Setup
All sensitive values are stored in Jenkins credentials store — never hardcoded in the Jenkinsfile. Go to: Manage Jenkins → Credentials → System → Global credentials → Add Credentials.

Component / Step	Description
git-credentials	Username with password · GitHub username + Personal Access Token · used in Checkout stage
nexus-credentials	Username with password · Nexus admin username + password · used in Push to Nexus stage
docker-hub-repo	Username with password · ighojohn + Docker Hub password · used in Push to Docker Hub stage

💡 GitHub removed password auth in 2021 — always use a Personal Access Token not your password

How to Create a GitHub Personal Access Token
1.	Go to GitHub → Settings → Developer settings
2.	Click Personal access tokens → Tokens (classic)
3.	Click Generate new token (classic)
4.	Give it a name: Jenkins
5.	Select scope: repo (tick the whole repo section)
6.	Click Generate token
7.	Copy the token immediately — it is only shown once!
8.	In Jenkins → Add Credentials → paste token as Password
 
Complete Jenkinsfile
This is the complete corrected Jenkinsfile for this project stored at the repo root or in a subfolder:

#!/usr/bin/env groovy
pipeline {
  agent any
  tools {
    maven 'maven-3.9'
  }
  stages {

    stage('Checkout') {
      steps { checkout scm }
    }

    stage('Test') {
      steps {
        script {
          echo 'Running tests...'
          sh 'mvn test'
        }
      }
    }

    stage('Build JAR') {
      steps {
        script {
          echo 'Building the application...'
          sh 'mvn clean package'
        }
      }
    }

    stage('Build Image') {
      steps {
        script {
          echo 'Building Docker image...'
          sh 'docker build -t ighojohn/demo-app:2.1 .'
        }
      }
    }

    stage('Push to Docker Hub') {
      steps {
        script {
          withCredentials([usernamePassword(
            credentialsId: 'docker-hub-repo',
            usernameVariable: 'DOCKER_USER',
            passwordVariable: 'DOCKER_PASS'
          )]) {
            sh 'echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin'
            sh 'docker push ighojohn/demo-app:2.1'
          }
        }
      }
    }

    stage('Deploy') {
      steps {
        script {
          echo 'Deploying application...'
          sh 'docker compose up -d'
        }
      }
    }

  }
  post {
    success { echo 'Pipeline succeeded! Image at ighojohn/demo-app:2.1 ✅' }
    failure { echo 'Pipeline failed! Check Jenkins logs ❌' }
  }
}
 
Common Errors & Fixes

Component / Step	Description
Couldn't find any revision to build	Branch set to */master but repo uses */main — change to */main in job config
Could not find credentials entry with ID ''	Credentials dropdown is empty — select the credential from the dropdown and save
mvn: not found	Maven not installed in Jenkins — install via Manage Jenkins → Tools → Maven installations
Tool type maven does not have install of Maven	Tool name mismatch — use exact name from Jenkins Tools e.g. maven-3.9 not Maven
No such DSL method buildApp	Function called in Jenkinsfile does not exist in script.groovy — add the function and return this
NoSuchFileException: script.groovy	Wrong path in load command — use full path: Jenkinsfile-syntax/script.groovy
permission denied docker.sock	Jenkins user cannot access Docker — run: sudo chmod 666 /var/run/docker.sock
failed to resolve openjdk:8-jre-alpine	Image deprecated — change to: FROM eclipse-temurin:17-jre-alpine
angent / scrript / withcredential	Groovy typos in Jenkinsfile — check spelling of all keywords carefully
 
Quick Reference Commands

Jenkins Container
docker start jenkins                                    # start Jenkins
docker logs jenkins                                     # view logs
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword

Nexus Container
docker start nexus                                      # start Nexus
docker exec -it nexus sh                               # enter container
docker exec nexus cat /nexus-data/admin.password       # get admin password

Docker Network
docker network create jenkins-nexus-network            # create network
docker network inspect jenkins-nexus-network           # check connections

Docker Hub
docker login                                           # login to Docker Hub
docker tag java-app:2.1 ighojohn/demo-app:2.1         # tag image
docker push ighojohn/demo-app:2.1                     # push to Docker Hub
docker pull ighojohn/demo-app:2.1                     # pull from Docker Hub

Fix Permissions
sudo chmod 666 /var/run/docker.sock                   # fix docker permission
sudo usermod -aG docker ubuntu                         # permanent fix
newgrp docker                                          # apply without logout

git push is all it takes — Jenkins handles everything else automatically. 🚀
GitHub: github.com/igho-john/build-java-maven-app-with-jenkin-  ·  DockerHub: ighojohn/demo-app
test webhook trigger
test webhook
test webhook
