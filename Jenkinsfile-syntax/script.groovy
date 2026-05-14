def buildApp() {
    echo 'Building the application...'
    sh 'mvn package'
}

def testApp() {
    echo 'Testing the application...'
    sh 'mvn test'
}

def deployApp() {
    echo 'Deploying the application...'
    sh 'docker build -t java-maven-app:1.0 .'
    sh 'docker push ighojohn/demo-app:1.0'
}

return this
