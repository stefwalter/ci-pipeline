#!/usr/bin/groovy
package org.centos.pipeline

import org.centos.*

import groovy.json.JsonSlurper

/**
 * Library to setup and configure the host the way ci-pipeline requires
 * @param stage
 * @param sshKey
 * @return
 */
def setupStage(String stage, String sshKey) {
    echo "Currently in stage: ${stage} in setupStage"

    // TODO: Either remove sshKey arg, or determine how to invoke second credentialsID and variable name based on arg.
    // Currently having an sshKey isn't that useful as we're still hard-coding the public credentialsID entry
    withCredentials([file(credentialsId: sshKey, variable: 'FEDORA_ATOMIC_KEY'),
                     file(credentialsId: 'fedora-atomic-pub-key', variable: 'FEDORA_ATOMIC_PUB_KEY')]) {
        sh '''
            #!/bin/bash
            set -xeuo pipefail

            mkdir -p ~/.ssh
            cp ${FEDORA_ATOMIC_KEY} ~/.ssh/id_rsa
            cp ${FEDORA_ATOMIC_PUB_KEY} ~/.ssh/id_rsa.pub
            chmod 600 ~/.ssh/id_rsa
            chmod 644 ~/.ssh/id_rsa.pub
            
            # Keep compatibility with earlier cciskel-duffy
            if test -f ${ORIGIN_WORKSPACE}/inventory.${ORIGIN_BUILD_TAG}; then
                ln -fs ${ORIGIN_WORKSPACE}/inventory.${ORIGIN_BUILD_TAG} ${WORKSPACE}/inventory
            fi
    
            if test -n "${playbook:-}"; then
                ansible-playbook --private-key=${FEDORA_ATOMIC_KEY} -u root -i ${WORKSPACE}/inventory "${playbook}"
            else
                ansible --private-key=${FEDORA_ATOMIC_KEY} -u root -i ${WORKSPACE}/inventory all -m ping
            fi
            exit
        '''
    }
}

/**
 * Library to execute a task and rsync the logs back to artifacts.ci.centos.org
 * @param stage
 * @param duffyKey
 * @return
 */
def runTaskAndReturnLogs(String stage, String duffyKey) {
    echo "Currently in stage: ${stage} in runTaskAndReturnLogs"

    withCredentials([file(credentialsId: duffyKey, variable: 'DUFFY_KEY'),
                     file(credentialsId: 'fedora-keytab', variable: 'FEDORA_KEYTAB')]) {
        sh '''
            #!/bin/bash
            set -xeuo pipefail

            echo $HOME
                
            cp ${DUFFY_KEY} ~/duffy.key
            chmod 600 ~/duffy.key
    
            cp ${FEDORA_KEYTAB} fedora.keytab
            chmod 0600 fedora.keytab

            echo "Host *.ci.centos.org" > ~/.ssh/config
            echo "    StrictHostKeyChecking no" >> ~/.ssh/config
            echo "    UserKnownHostsFile /dev/null" >> ~/.ssh/config
            chmod 600 ~/.ssh/config
            
            source ${ORIGIN_WORKSPACE}/task.env
            (echo -n "export RSYNC_PASSWORD=" && cat ~/duffy.key | cut -c '-13') > rsync-password.sh
            
            rsync -Hrlptv --stats -e ssh ${ORIGIN_WORKSPACE}/task.env rsync-password.sh fedora.keytab builder@${DUFFY_HOST}:${JENKINS_JOB_NAME}
            for repo in ci-pipeline sig-atomic-buildscripts; do
                rsync -Hrlptv --stats --delete -e ssh ${repo}/ builder@${DUFFY_HOST}:${JENKINS_JOB_NAME}/${repo}
            done
            
            # Use the following in ${task} to authenticate.
            #kinit -k -t ${FEDORA_KEYTAB} ${FEDORA_PRINCIPAL}
            build_success=true
            if ! ssh -tt builder@${DUFFY_HOST} "pushd ${JENKINS_JOB_NAME} && . rsync-password.sh && . task.env && ${task}"; then
                build_success=false
            fi
            
            rsync -Hrlptv --stats -e ssh builder@${DUFFY_HOST}:${JENKINS_JOB_NAME}/logs/ ${ORIGIN_WORKSPACE}/logs || true
            # Exit with code from the build
            if test "${build_success}" = "false"; then
                echo 'Build failed, see logs above'; exit 1
            fi
            exit
        '''
    }
}

/**
 * Library to check last image
 * @param stage
 * @return
 */
def checkLastImage(String stage) {
    echo "Currently in stage: ${stage} in checkLastImage"

    sh '''
        set +e
                
        header=$(curl -sI "${HTTP_BASE}/${branch}/images/latest-atomic.qcow2"|grep -i '^Last-Modified:')
        curl_rc=$?
        if [ ${curl_rc} -eq 0 ]; then
            l_modified=$(echo ${header}|sed s'/Last-Modified: //')
            prev=$( date --date="$l_modified" +%s )
            cur=$( date +%s )
            if [ $((cur - prev)) -gt 86400 ]; then
                echo "New atomic image needed. Existing atomic image is more than 24 hours old"
                touch ${WORKSPACE}/NeedNewImage.txt
        
            else
                echo "No new atomic image need. Existing atomic image is less than 24 hours old"
            fi
        else
            echo "New atomic image needed. Unable to find existing atomic image"
            touch ${WORKSPACE}/NeedNewImage.txt
        
        fi
    '''
}

/**
 *
 *
 * variables
 *  stage - current stage running
 *  imageFilePath - path to the file to examine last modified time for. Defaults to 'images/latest-atomic.qcow2'
 */
/**
 * Library to check last modified date for a given image file.
 * @param stage
 * @param imageFilePath
 * @return
 */
def checkImageLastModifiedTime(String stage, String imageFilePath='images/latest-atomic.qcow2'){

    def url = new URL("${HTTP_BASE}/${branch}/${imageFilePath}")
    def fileName = imageFilePath.split('/')[-1]
    def filePath = imageFilePath.split('/')[0..-2].join('/').replaceAll("^/", "")

    echo "Currently in stage: ${stage} in checkImageLastModifiedTime for ${fileName} in /${filePath}/"

    def connection = (HttpURLConnection)url.openConnection()
    connection.setRequestMethod("HEAD")

    try {
        connection.connect()
        def reponseCode = connection.getResponseCode()
        def needNewImage = false

        if (reponseCode == 200) {
            // Get our last modified date for the file in milliseconds
            def lastModifiedDate = connection.getLastModified()

            // Create a calendar instance for right now and subtract 24 hours,
            // then get that time in milliseconds
            def calendar = Calendar.getInstance()
            calendar.add(Calendar.HOUR_OF_DAY, -24) // 24 hours ago
            def comparisonDate = calendar.getTimeInMillis()

            // Determine if our last modified date is greater than or equal to 24 hours ago.
            if ( lastModifiedDate <= comparisonDate ) {
                echo "Creating new image. Last modified time of existing image >= 24 hours ago."
                needNewImage = true
            } else {
                echo "Not creating new image. Last modified time of existing image is < 24 hours ago."
            }
        } else if (reponseCode == 404) {
            echo "Creating new image. Unable to locate existing image."
            needNewImage = true
        } else {
            echo "Error: ${connection.responseCode}: ${connection.getResponseMessage()}"
            echo "Creating new image due to some error when getting last modified time of previous image"
            needNewImage = true
        }

        if (needNewImage) {
            new File("${WORKSPACE}/NeedNewImage.txt").createNewFile()
        }

    } catch (err) {
        echo "There was a fatal error getting the last modified time: ${err}, unable to determine if new image is needed"
    }
}


/**
 * Library to set message fields to be published
 * @param messageType: ${MAIN_TOPIC}.ci.pipeline.<defined-in-README>
 * @return
 */
def setMessageFields(String messageType){
    topic = "${env.MAIN_TOPIC}.ci.pipeline.${messageType}"
    messageProperties = "topic=${topic}\n" +
                        "build_url=${env.BUILD_URL}\n" +
                        "build_id=${env.BUILD_ID}\n" +
                        "branch=${env.branch}\n" +
                        "compose_rev=${env.commit}\n" +
                        "namespace=${env.fed_namespace}\n" +
                        "ref=fedora/${env.branch}/${env.basearch}/atomic-host\n" +
                        "repo=${env.fed_repo}\n" +
                        "original_spec_nvr=${env.original_spec_nvr}\n" +
                        "nvr=${env.nvr}\n" +
                        "rev=${env.fed_rev}\n" +
                        "test_guidance=''\n" +
                        "username=${env.RSYNC_USER}\n" +
                        "status=${currentBuild.currentResult}\n"
    messageContent=''

    if (messageType == 'compose.running') {
        messageProperties = messageProperties +
                "compose_url=${env.HTTP_BASE}/${env.branch}/ostree\n"
                "compose_rev=''\n"
    } else if ((messageType == 'compose.complete') || (messageType == 'test.integration.queued') ||
            (messageType == 'test.integration.running') || (messageType == 'test.integration.complete')) {
        messageProperties = messageProperties +
            "compose_url=${env.HTTP_BASE}/${env.branch}/ostree\n"
            "compose_rev=${env.commit}\n"
    } else if (messageType == 'image.running') {
            messageProperties = messageProperties +
                "compose_url=${env.HTTP_BASE}/${env.branch}/ostree\n"
                "compose_rev=${env.commit}\n" +
                "image_url=''\n" +
                "image_name=''\n" +
                "type=qcow2\n"
    } else if ((messageType == 'image.complete') || (messageType == 'test.smoke.running') ||
            (messageType == 'test.smoke.compelete')) {
        messageProperties = messageProperties +
                "compose_url=${env.HTTP_BASE}/${env.branch}/ostree\n"
                "compose_rev=${env.commit}\n" +
                "image_url=${env.image2boot}\n" +
                "image_name=${env.image_name}\n" +
                "type=qcow2\n"
    } else {
        return [ 'topic': topic, 'properties': messageProperties, 'content': messageContent ]
    }
    return [ 'topic': topic, 'properties': messageProperties, 'content': messageContent ]
}

/**
 * Library to send message
 * @param msgProps - The message properties in key=value form, one key/value per line ending in '\n'
 * @param msgContent - Message content.
 * @return
 */
def sendMessage(String msgProps, String msgContent) {
    sendCIMessage messageContent: msgContent,
            messageProperties: msgProps,
            messageType: 'Custom',
            overrides: [topic: "${topic}"],
            providerName: "${MSG_PROVIDER}"
}

/**
 * Library to parse CI_MESSAGE and inject its key/value pairs as env variables.
 *
 */
def injectFedmsgVars() {

    // Parse the CI_MESSAGE into a Map
    def ci_data = new JsonSlurper().parseText(env.CI_MESSAGE)

    // If we have a 'commit' key in the CI_MESSAGE, for each key under 'commit', we
    // * prepend the key name with fed_
    // * replace any '-' with '_'
    // * truncate the value for the key at the first '\n' character
    // * replace any double-quote characters with single-quote characters in the value for the key.

    if (ci_data['commit']) {
        ci_data.commit.each { key, value ->
            env."fed_${key.toString().replaceAll('-', '_')}" =
                    value.toString().split('\n')[0].replaceAll('"', '\'')
        }
        if (env.fed_branch == 'master'){
            env.branch = 'rawhide'
        } else {
            env.branch = env.fed_branch
        }
    }
}

/**
 * Library to prepare credentials
 * @return
 */
def prepareCredentials() {
    withCredentials([file(credentialsId: 'fedora-keytab', variable: 'FEDORA_KEYTAB')]) {
        sh '''
            #!/bin/bash
            set -xeuo pipefail
    
            cp ${FEDORA_KEYTAB} fedora.keytab
            chmod 0600 fedora.keytab
            
            mkdir -p ~/.ssh

            echo "Host *.ci.centos.org" > ~/.ssh/config
            echo "    StrictHostKeyChecking no" >> ~/.ssh/config
            echo "    UserKnownHostsFile /dev/null" >> ~/.ssh/config
            chmod 600 ~/.ssh/config
        '''
    }
    // Initialize RSYNC_PASSWORD from credentialsId
    env.RSYNC_PASSWORD = getPasswordFromDuffyKey('duffy-key')
}
/**
 * Library to set default environmental variables. Performed once at start of Jenkinsfile
 * @param envMap: Key/value pairs which will be set as environmental variables.
 * @return
 */
def setDefaultEnvVars(Map envMap=null){

    // Check if we're working with a staging or production instance by
    // evaluating if env.ghprbActual is null, and if it's not, whether
    // it is something other than 'master'
    // If we're working with a staging instance:
    //      We default to an MAIN_TOPIC of 'org.centos.stage'
    // If we're working with a production instance:
    //      We default to an MAIN_TOPIC of 'org.centos.prod'
    // Regardless of whether we're working with staging or production,
    // if we're provided a value for MAIN_TOPIC in the build parameters:

    if (env.ghprbActualCommit != null && env.ghprbActualCommit != "master") {
        env.MAIN_TOPIC = env.MAIN_TOPIC ?: 'org.centos.stage'
    } else {
        env.MAIN_TOPIC = env.MAIN_TOPIC ?: 'org.centos.prod'
    }

    // Set our base HTTP_SERVER value
    env.HTTP_SERVER = env.HTTP_SERVER ?: 'http://artifacts.ci.centos.org'

    // Set our base RSYNC_SERVER value
    env.RSYNC_SERVER = env.RSYNC_SERVER ?: 'artifacts.ci.centos.org'
    env.RSYNC_USER = env.RSYNC_USER ?: 'fedora-atomic'

    // Check if we're working with a staging or production instance by
    // evaluating if env.ghprbActual is null, and if it's not, whether
    // it is something other than 'master'
    // If we're working with a staging instance:
    //      We default to an RSYNC_DIR of fedora-atomic/staging
    //      We default to an HTTP_DIR of fedora-atomic/staging
    // If we're working with a production instance:
    //      We default to an RSYNC_DIR of fedora-atomic
    //      We default to an HTTP_DIR of fedora-atomic
    // Regardless of whether we're working with staging or production,
    // if we're provided a value for RSYNC_DIR or HTTP_DIR in the build parameters:
    //      We set the RSYNC_DIR or HTTP_DIR to the value(s) provided (this overwrites staging or production paths)

    if (env.ghprbActualCommit != null && env.ghprbActualCommit != "master") {
        env.RSYNC_DIR = env.RSYNC_DIR ?: 'fedora-atomic/staging'
        env.HTTP_DIR = env.HTTP_DIR ?: 'fedora-atomic/staging'
    } else {
        env.RSYNC_DIR = env.RSYNC_DIR ?: 'fedora-atomic'
        env.HTTP_DIR = env.HTTP_DIR ?: 'fedora-atomic'
    }

    // Set env.HTTP_BASE to our env.HTTP_SERVER/HTTP_DIR,
    //  ex: http://artifacts.ci.centos.org/fedora-atomic/ (production)
    //  ex: http://artifacts.ci.centos.org/fedora-atomic/staging (staging)
    env.HTTP_BASE = "${env.HTTP_SERVER}/${env.HTTP_DIR}"

    env.MSG_PROVIDER = env.MSG_PROVIDER ?: 'fedora-fedmsg'
    env.basearch = env.basearch ?: 'x86_64'
    env.OSTREE_BRANCH = env.OSTREE_BRANCH ?: ''
    env.commit = env.commit ?: ''
    env.image2boot = env.image2boot ?: ''
    env.image_name = env.image_name ?: ''
    env.FEDORA_PRINCIPAL = env.FEDORA_PRINCIPAL ?: 'bpeck/jenkins-continuous-infra.apps.ci.centos.org@FEDORAPROJECT.ORG'
    env.package_url = env.package_url ?: ''
    env.nvr = env.nvr ?: ''
    env.original_spec_nvr = env.original_spec_nvr ?: ''
    env.ANSIBLE_HOST_KEY_CHECKING = env.ANSIBLE_HOST_KEY_CHECKING ?: 'False'

    // If we've been provided an envMap, we set env.key = value
    // Note: This may overwrite above specified values.
    envMap.each { key, value ->
        env."${key.toSTring().trim()}" = value.toString().trim()
    }
}

/**
 * Library to set stage specific environmental variables.
 * @param stage - Current stage
 * @return
 */
def setStageEnvVars(String stage){
    def stages =
            ["ci-pipeline-rpmbuild"                : [
                    task                     : "./ci-pipeline/tasks/rpmbuild-test",
                    playbook                 : "ci-pipeline/playbooks/setup-rpmbuild-system.yml",
                    ref                      : "fedora/${env.branch}/${env.basearch}/atomic-host",
                    repo                     : "${env.fed_repo}",
                    rev                      : "${env.fed_rev}",
            ],
             "ci-pipeline-ostree-compose"          : [
                     task                     : "./ci-pipeline/tasks/ostree-compose",
                     playbook                 : "ci-pipeline/playbooks/rdgo-setup.yml",
                     ref                      : "fedora/${env.branch}/${env.basearch}/atomic-host",
                     repo                     : "${env.fed_repo}",
                     rev                      : "${env.fed_rev}",
                     basearch                 : "x86_64",
             ],
             "ci-pipeline-ostree-image-compose"    : [
                     task                     : "./ci-pipeline/tasks/ostree-image-compose",
                     playbook                 : "ci-pipeline/playbooks/rdgo-setup.yml",

             ],
             "ci-pipeline-ostree-image-boot-sanity": [
                     task                     : "./ci-pipeline/tasks/ostree-image-compose",
                     playbook                 : "ci-pipeline/playbooks/system-setup.yml",
             ],
             "ci-pipeline-ostree-boot-sanity"      : [
                     task    : "./ci-pipeline/tasks/ostree-boot-image",
                     playbook: "ci-pipeline/playbooks/system-setup.yml",
                     DUFFY_OP: "--allocate"
             ],
             "ci-pipeline-atomic-host-tests"       : [
                     task    : "./ci-pipeline/tasks/atomic-host-tests",
                     playbook: "ci-pipeline/playbooks/system-setup.yml",
             ]
            ]

    // Get the map of env var keys and values and write them to the env global variable
    stages.get(stage).each { key, value ->
        env."${key}" = value
    }
}

/**
 * Library to create a text string which is written to the file 'task.env' in the {env.ORIGIN_WORKSPACE} and call
 * runTaskAndReturnLogs()
 * @param stage - Current stage
 * @return
 */
def rsyncData(String stage){
    def text = "export JENKINS_JOB_NAME=\"${env.JOB_NAME}-${stage}\"\n" +
            "export RSYNC_USER=\"${env.RSYNC_USER}\"\n" +
            "export RSYNC_SERVER=\"${env.RSYNC_SERVER}\"\n" +
            "export RSYNC_DIR=\"${env.RSYNC_DIR}\"\n" +
            "export FEDORA_PRINCIPAL=\"${env.FEDORA_PRINCIPAL}\"\n" +
            "export JENKINS_BUILD_TAG=\"${env.BUILD_TAG}-${stage}\"\n" +
            "export OSTREE_BRANCH=\"${env.OSTREE_BRANCH}\"\n"

    if (stage in ['ci-pipeline-ostree-compose', 'ci-pipeline-ostree-image-compose',
                         'ci-pipeline-ostree-image-boot-sanity', 'ci-pipeline-ostree-boot-sanity']) {
        text = text +
                "export HTTP_BASE=\"${env.HTTP_BASE}\"\n" +
                "export branch=\"${env.branch}\"\n"
    }
    if (stage == 'ci-pipeline-rpmbuild') {
        text = text +
                "export fed_repo=\"${env.fed_repo}\"\n" +
                "export fed_branch=\"${env.fed_branch}\"\n" +
                "export fed_rev=\"${env.fed_rev}\"\n"

    } else if (stage == 'ci-pipeline-ostree-image-boot-sanity') {
        text = text +
                "export ANSIBLE_HOST_KEY_CHECKING=\"False\"\n"
    } else if (stage == 'ci-pipeline-ostree-boot-sanity') {
        text = text +
                "export fed_repo=\"${env.fed_repo}\"\n" +
                "export image2boot=\"${env.image2boot}\"\n" +
                "export commit=\"${env.commit}\"\n" +
                "export ANSIBLE_HOST_KEY_CHECKING=\"False\"\n"
    }

    writeFile file: "${env.ORIGIN_WORKSPACE}/task.env",
            text: text
    runTaskAndReturnLogs(stage, 'duffy-key')

}

/**
 * Library to provision resources used in the stage
 * @param stage - Current stage
 * @return
 */
def provisionResources(String stage){
    def utils = new Utils()

    utils.allocateDuffyCciskel(stage)

    echo "Duffy Allocate ran for stage ${stage} with option --allocate\r\n" +
            "ORIGIN_WORKSPACE=${env.ORIGIN_WORKSPACE}\r\n" +
            "ORIGIN_BUILD_TAG=${env.ORIGIN_BUILD_TAG}\r\n" +
            "ORIGIN_CLASS=${env.ORIGIN_CLASS}"

    job_props = "${env.ORIGIN_WORKSPACE}/job.props"
    job_props_groovy = "${env.ORIGIN_WORKSPACE}/job.groovy"
    utils.convertProps(job_props, job_props_groovy)
    load(job_props_groovy)

}

/**
 * Library to execute script in container
 * Container must have been defined in a podTemplate
 *
 * @param containerName Name of the container for script execution
 * @param script Complete path to the script to execute
 * @return
 */
def executeInContainer(String stageName, String containerName, String script) {
    //
    // Kubernetes plugin does not let containers inherit
    // env vars from host. We force them in.
    //
    containerEnv = env.getEnvironment().collect { key, value -> return key+'='+value }
    withEnv(containerEnv) {
        container(containerName) {
            sh 'pwd'
            sh 'ls -l /tmp'
            sh "cp -fv ${WORKSPACE}/fedora.keytab /home/fedora.keytab"
            sh 'env'
            sh script
            sh "ls -lR logs || true"
        }
    }
    sh "mkdir -p " + stageName
    sh "mv -vf logs " + stageName + "/logs || true"
}

/**
 *
 * @param openshiftProject name of openshift namespace/project.
 * @param nodeName podName we are going to verify.
 * @return
 */
def verifyPod(String openshiftProject, String nodeName) {
    openshift.withCluster() {
        openshift.withProject(openshiftProject) {
            def describeStr = openshift.selector("pods", nodeName).describe()
            out = describeStr.out.trim()
            writeFile file: 'node-pod-description-' + nodeName + '.txt',
                    text: out
            archiveArtifacts 'node-pod-description-' + nodeName + '.txt'

            timeout(60) {
                echo "Ensuring all containers are running in pod: ${env.NODE_NAME}"
                echo "Container names in pod ${env.NODE_NAME}: "
                def names       = openshift.raw("get", "pod",  "${env.NODE_NAME}", '-o=jsonpath="{.status.containerStatuses[*].name}"')
                echo names.out.trim()

                waitUntil {
                    def readyStates = openshift.raw("get", "pod",  "${env.NODE_NAME}", '-o=jsonpath="{.status.containerStatuses[*].ready}"')

                    echo "Container statuses: "
                    echo readyStates.out.trim()
                    def anyNotReady = readyStates.out.trim().contains("false")
                    if (anyNotReady) {
                        echo "One or more containers not ready...see above message ^^"
                        return false
                    } else {
                        echo "All containers ready!"
                        return true
                    }
                }
            }
        }
    }
}
/**
 *
 * @param credentialsId Credential ID for Duffy Key
 * @return password
 */
def getPasswordFromDuffyKey(String credentialsId) {
    withCredentials([file(credentialsId: credentialsId, variable: 'DUFFY_KEY')]) {
        return sh(script: 'cat ' + DUFFY_KEY +
                ' | cut -c \'-13\'', returnStdout: true).trim()
    }
}

/**
 * Library to teardown resources used in the current stage
 *
 * variables
 *   currentStage - current stage running
 */
def teardownResources(String stage){
    def utils = new Utils()

    utils.teardownDuffyCciskel(stage)

    echo "Duffy Deallocate ran for stage ${stage} with option --teardown\r\n" +
            "DUFFY_HOST=${env.DUFFY_HOST}"
}

/**
 * Based on tagMap, add comment to GH with
 * instructions to manual commands
 *
 * @param map of tags
 * @return
 */
def sendPRCommentforTags(imageOperationsList) {
    if (imageOperationsList.size() == 0) {
        return
    }
    def msg = "\nThe following image promotions have taken place:\n\n"
    imageOperationsList.each {
        msg = msg + "+ ${it}\n"
    }

    echo "Prepare GHI tool"
    withCredentials([string(credentialsId: 'paas-bot', variable: 'TOKEN')]) {
        sh "git config --global ghi.token ${TOKEN}"
        sh 'curl -sL https://raw.githubusercontent.com/stephencelis/ghi/master/ghi > ghi && chmod 755 ghi'
        sh './ghi comment ' + env.ghprbPullId + ' -m "' + msg + '"'
    }
}

/**
 * info about tags to be used
 * @param map
 */
def printLabelMap(map) {
    for (tag in map) {
        echo "tag to be used for ${tag.key} -> ${tag.value}"
    }
}

/**
 * Build image in openshift
 * @param openshiftProject Openshift Project
 * @param buildConfig
 * @return
 */
def buildImage(String openshiftProject, String buildConfig) {
    // - build in Openshift
    // - startBuild with a commit
    // - Get result Build and get imagestream manifest
    // - Use that to create a unique tag
    // - This tag will then be passed as an image input
    //   to the podTemplate/containerTemplate to create
    //   our slave pod.
    openshift.withCluster() {
        openshift.withProject(openshiftProject) {
            def result = openshift.startBuild(buildConfig,
                    "--commit",
                    "refs/pull/" + env.ghprbPullId + "/head",
                    "--wait")
            def out = result.out.trim()
            echo "Resulting Build: " + out

            def describeStr = openshift.selector(out).describe()
            out = describeStr.out.trim()

            def imageHash = sh(
                    script: "echo \"${out}\" | grep 'Image Digest:' | cut -f2- -d:",
                    returnStdout: true
            ).trim()
            echo "imageHash: ${imageHash}"

            echo "Creating CI tag for ${openshiftProject}/${buildConfig}: ${buildConfig}:PR-${env.ghprbPullId}"

            openshift.tag("${openshiftProject}/${buildConfig}@${imageHash}",
                    "${openshiftProject}/${buildConfig}:PR-${env.ghprbPullId}")

            return "PR-" + env.ghprbPullId
        }
    }
}

/**
 * Using the currentBuild, get a string representation
 * of the changelog.
 * @return String of changelog
 */
@NonCPS
def getChangeLogFromCurrentBuild() {
    MAX_MSG_LEN = 100
    def changeString = ""

    echo "Gathering SCM changes"
    def changeLogSets = currentBuild.changeSets
    for (int i = 0; i < changeLogSets.size(); i++) {
        def entries = changeLogSets[i].items
        for (int j = 0; j < entries.length; j++) {
            def entry = entries[j]
            truncated_msg = entry.msg.take(MAX_MSG_LEN)
            changeString += " - ${truncated_msg} [${entry.author}]\n"
            def files = new ArrayList(entry.affectedFiles)
            for (int k = 0; k < files.size(); k++) {
                def file = files[k]
                changeString += "    | (${file.editType.name})  ${file.path}\n"
            }
        }
    }

    if (!changeString) {
        changeString = " - No new changes\n"
    }
    return changeString
}