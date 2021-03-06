import org.centos.pipeline.PipelineUtils
import org.centos.Utils

/**
 * A class of methods used in the Jenkinsfile pipeline.
 * These methods are wrappers around methods in the ci-pipeline library.
 */
class pipelineUtils implements Serializable {

    def pipelineUtils = new PipelineUtils()
    def utils = new Utils()

    /**
     * Method to setup and configure the host the way ci-pipeline requires
     *
     * @param stage Current stage
     * @param sshKey Name of ssh key to use
     * @return
     */
    def setupStage(String stage, String sshKey) {
        pipelineUtils.setupStage(stage, sshKey)
    }

    /**
     * Method to to check last image modified time
     * @param stage Current stage
     * @return
     */
    def checkLastImage(String stage) {
        pipelineUtils.checkLastImage(stage)
    }

    /**
     * Method to set message fields to be published
     * @param messageType ${MAIN_TOPIC}.ci.pipeline.<defined-in-README>
     * @return
     */
    def setMessageFields(String messageType) {
        pipelineUtils.setMessageFields(messageType)
    }

    /**
     * Method to send message
     * @param msgProps The message properties in key=value form, one key/value per line ending in '\n'
     * @param msgContent Message content.
     * @return
     */
    def sendMessage(String msgProps, String msgContent) {
        pipelineUtils.sendMessage(msgProps, msgContent)
    }

    /**
     * Method to parse CI_MESSAGE and inject its key/value pairs as env variables.
     * @return
     */
    def injectFedmsgVars() {
        pipelineUtils.injectFedmsgVars()
    }

    /**
     * Method to set default environmental variables. Performed once at start of Jenkinsfile
     * @param envMap Key/value pairs which will be set as environmental variables.
     * @return
     */
    def setDefaultEnvVars(Map envMap = null) {
        pipelineUtils.setDefaultEnvVars(envMap)
    }

    /**
     * Method to set stage specific environmental variables.
     * @param stage Current stage
     * @return
     */
    def setStageEnvVars(String stage) {
        pipelineUtils.setStageEnvVars(stage)
    }

    /**
     * Method to create a text string which is written to the file 'task.env' in the {env.ORIGIN_WORKSPACE} and call
     * runTaskAndReturnLogs()
     * @param stage Current stage
     * @return
     */
    def rsyncData(String stage) {
        pipelineUtils.rsyncData(stage)
    }

    /**
     * Method to provision resources used in the stage
     * @param stage Current stage
     * @return
     */
    def provisionResources(String stage) {
        pipelineUtils.provisionResources(stage)
    }

    /**
     * Method to teardown resources used in the stage
     * @param stage Current stage
     * @return
     */
    def teardownResources(String stage) {
      pipelineUtils.teardownResources(stage)
    }

    def verifyPod(openshiftProject, nodeName) {
        pipelineUtils.verifyPod(openshiftProject, nodeName)
    }

    def prepareCredentials() {
        pipelineUtils.prepareCredentials()
    }
    def executeInContainer(stageName, containerName, script) {
        pipelineUtils.executeInContainer(stageName, containerName, script)
    }
        
    /**
     * Method to prepend 'env.' to the keys in source file and write them in a format of env.key=value in the destination file.
     * @param sourceFile The file to read from
     * @param destinationFile The file to write to
     * @return
     */
    def convertProps(String sourceFile, String destinationFile) {
        utils.convertProps(sourceFile, destinationFile)
    }

    /**
     * Send comment to GH about image operations.
     * @param imageOperationsList list of image operation messages
     * @return
     */
    def sendPRCommentforTags(imageOperationsList) {
        pipelineUtils.sendPRCommentforTags(imageOperationsList)
    }

    /**
     * info about tags to be used
     * @param map
     */
    def printLabelMap(map) {
        pipelineUtils.printLabelMap(map)
    }

    /**
     * Build image in openshift
     * @param buildConfig
     * @return
     */
    /**
     * Build image in openshift
     * @param openshiftProject Openshift Project
     * @param buildConfig
     * @return
     */
    def buildImage(String openshiftProject, String buildConfig) {
        return pipelineUtils.buildImage(openshiftProject, buildConfig)
    }

    /**
     * Using the currentBuild, get a string representation
     * of the changelog.
     * @return String of changelog
     */
    @NonCPS
    def getChangeLogFromCurrentBuild() {
        pipelineUtils.getChangeLogFromCurrentBuild()
    }
}