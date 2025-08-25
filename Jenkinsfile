pipeline {
    agent { label "devel12" }

    environment {
        MAVEN_OPTS = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
    }

    triggers {
        pollSCM("H/3 * * * *")
        upstream('/Docker-payara5-bump-trigger')
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
                    #mvn -B -Dmaven.repo.local=\$WORKSPACE/.repo org.jacoco:jacoco-maven-plugin:prepare-agent install javadoc:aggregate -Dsurefire.useFile=false -Dmaven.test.failure.ignore
                    mvn -B -Dmaven.repo.local=\$WORKSPACE/.repo install javadoc:aggregate -Dsurefire.useFile=false -Dmaven.test.failure.ignore
                """
                script {
                    junit testResults: '**/target/surefire-reports/TEST-*.xml'

                    def java = scanForIssues tool: [$class: 'Java']
                    def javadoc = scanForIssues tool: [$class: 'JavaDoc']

                    publishIssues issues:[java,javadoc], unstableTotalAll:1000
                }
            } 
        }

        stage("analysis") {
            steps {
                sh """
                    mvn -B -Dmaven.repo.local=\$WORKSPACE/.repo pmd:pmd pmd:cpd spotbugs:spotbugs -Dspotbugs.excludeFilterFile=src/test/spotbugs/spotbugs-exclude.xml
                """

                script {
                    def pmd = scanForIssues tool: [$class: 'Pmd'], pattern: '**/target/pmd.xml'
                    publishIssues issues:[pmd], unstableTotalAll:1000

                    def cpd = scanForIssues tool: [$class: 'Cpd'], pattern: '**/target/cpd.xml'
                    publishIssues issues:[cpd]

                    def spotbugs = scanForIssues tool: [$class: 'SpotBugs'], pattern: '**/target/spotbugsXml.xml'
                    publishIssues issues:[spotbugs], unstableTotalAll:1
                }
            }
        }

        stage("upload") {
            steps {
                script {
                    if (env.BRANCH_NAME == 'master') {
                        sh """
                            mvn -B -Dmaven.repo.local=\$WORKSPACE/.repo deploy -DskipTests -DskipITs
                        """
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                def version = readMavenPom().version
                writeFile(file: 'version.txt', text: version, encoding: 'UTF-8')
                archiveArtifacts artifacts: 'version.txt', fingerprint: true
            }
        }

        failure {
            script {
                if ("${env.BRANCH_NAME}" == 'master') {
                    emailext(
                            recipientProviders: [developers(), culprits()],
                            to: "de-team@dbc.dk",
                            subject: "[Jenkins] ${env.JOB_NAME} #${env.BUILD_NUMBER} failed",
                            mimeType: 'text/html; charset=UTF-8',
                            body: "<p>The master build failed. Log attached. </p><p><a href=\"${env.BUILD_URL}\">Build information</a>.</p>",
                            attachLog: true,
                    )
                    slackSend(channel: 'de-notifications',
                            color: 'warning',
                            message: "${env.JOB_NAME} #${env.BUILD_NUMBER} failed and needs attention: ${env.BUILD_URL}",
                            tokenCredentialId: 'slack-global-integration-token')

                } else {
                    // this is some other branch, only send to developer
                    emailext(
                            recipientProviders: [developers()],
                            subject: "[Jenkins] ${env.BUILD_TAG} failed and needs your attention",
                            mimeType: 'text/html; charset=UTF-8',
                            body: "<p>${env.BUILD_TAG} failed and needs your attention. </p><p><a href=\"${env.BUILD_URL}\">Build information</a>.</p>",
                            attachLog: false,
                    )
                }
            }
        }

        success {
            step([$class: 'JavadocArchiver', javadocDir: 'target/site/apidocs', keepAll: false])
            archiveArtifacts artifacts: '**/target/*-jar-with-dependencies.jar', fingerprint: true
        }
    }
}
