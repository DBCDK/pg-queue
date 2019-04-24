pipeline {
    agent { label "devel8" }
    tools {
        maven "maven 3.5"
    }
    environment {
        MAVEN_OPTS = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
    }
    triggers {
        pollSCM("H/3 * * * *")
    }
    options {
        buildDiscarder(logRotator(artifactDaysToKeepStr: "", artifactNumToKeepStr: "", daysToKeepStr: "30", numToKeepStr: "30"))
        timestamps()
    }
    stages {
        stage("build") {
            steps {
                // Fail Early..
                script {
                    if (! env.BRANCH_NAME) {
                        currentBuild.rawBuild.result = Result.ABORTED
                        throw new hudson.AbortException('Job Started from non MultiBranch Build')
                    } else {
                        println(" Building BRANCH_NAME == ${BRANCH_NAME}")
                    }

                }

                sh """
                    rm -rf \$WORKSPACE/.repo/dk/dbc
                    mvn -B -Dmaven.repo.local=\$WORKSPACE/.repo clean
                    mvn -B -Dmaven.repo.local=\$WORKSPACE/.repo install javadoc:aggregate -Dsurefire.useFile=false -Dmaven.test.failure.ignore
                """
                script {
                    junit testResults: '**/target/surefire-reports/TEST-*.xml'

                    def java = scanForIssues tool: [$class: 'Java']
                    def javadoc = scanForIssues tool: [$class: 'JavaDoc']

                    publishIssues issues:[java,javadoc], unstableTotalAll:1
                }
            } 
        }

        stage("analysis") {
            steps {
                sh """
                    mvn -B -Dmaven.repo.local=\$WORKSPACE/.repo pmd:pmd pmd:cpd findbugs:findbugs
                """

                script {
                    def pmd = scanForIssues tool: [$class: 'Pmd'], pattern: '**/target/pmd.xml'
                    publishIssues issues:[pmd], unstableTotalAll:1

                    def cpd = scanForIssues tool: [$class: 'Cpd'], pattern: '**/target/cpd.xml'
                    publishIssues issues:[cpd]

                    def findbugs = scanForIssues tool: [$class: 'FindBugs'], pattern: '**/target/findbugsXml.xml'
                    publishIssues issues:[findbugs], unstableTotalAll:1
                }
            }
        }

        stage("upload") {
            steps {
                script {
                    if (env.BRANCH_NAME ==~ /master|trunk/) {
                        sh """
                            mvn -Dmaven.repo.local=\$WORKSPACE/.repo jar:jar deploy:deploy
                        """
                    }
                }
            }
        }
    }
}
