def buildApp() {
    echo "building the application..."
    sh 'mvn package'
    withCredentials([usernamePassword(credentialsId: 'docker-hub-repo', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh 'docker build -t ighojohn/demo-app:jma-2.0 .'
        sh "echo $PASS | docker login -u $USER --password-stdin"
        sh 'docker push ighojohn/demo-app:jma-2.0'
    }
}

def testApp() {
    echo "running tests..."
    // add real test commands here later if needed
}

def deployApp() {
    echo 'deploying the application...'
}

return this
