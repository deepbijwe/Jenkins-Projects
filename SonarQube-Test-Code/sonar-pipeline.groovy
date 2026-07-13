pipeline {
    agent any

     stages {
        stage('CODE_PULL') {
            steps{
                git branch: 'master', 
                     url: 'https://github.com/mayurmwagh/onlinebookstore.git'
            }
        }
        stage('CODE_BUILD') {
            steps{
                sh 'mvn clean package' 
                }
            }
            
        
        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('SonarQubeServer') { // must match name from step 5
                    sh '''
                        mvn sonar:sonar \
                        -Dsonar.projectKey=onlinebookstore \
                        -Dsonar.host.url=http://<private_ip-of-sonarqube-server>:9000
                    '''
              }
           }
        }    
     }
  }    

