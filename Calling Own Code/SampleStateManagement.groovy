package org.petermac.clarity.tools

import groovy.xml.StreamingMarkupBuilder
import org.petermac.clarity.IClarityConfiguration
import org.petermac.clarity.clarityApi.ApiModel
import org.petermac.clarity.clarityApi.ClarityContainer
import org.petermac.clarity.clarityApi.GlsRestApiUtils
import org.petermac.clarity.clarityApi.IApiModel
import org.petermac.clarity.clarityApi.IClaritySample
import org.petermac.clarity.clarityApi.IClarityStep
import org.petermac.clarity.clarityApi.IGlsRestApiUtils
import org.petermac.clarity.entities.ChemagicFileItem
import org.petermac.clarity.entities.Enums
import org.petermac.logging.CsLogger
import org.petermac.util.Clarity

/**
 * Created by reid gareth on 2/11/2014.
 */
public interface ISampleStateManagement
{
    String[] GetNextStep(String stepUri)
    void AssignNextStage(String processUri)
    String StartNextStep(String processUri)
    String StartNextStepWithReplicates(String processUri, int replicates)
    void AdvanceNextInternalStep(currentStep)
    void AssignNextStep(String currentStep, String action)
    void SetNextAction(String stepUri, String action)
    void SetNextActionAsStep(String stepUri, String nextStepUri)
    void AssignNextStepForNormalizationPlates(String currentStep)
}

public class SampleStateManagement implements ISampleStateManagement
{
    public Enums.RobotMode RobotMode
    public Enums.Workflow Workflow
    public List<ChemagicFileItem> InputArtifacts
    private Clarity _clarityCredentials
    private IToolBox _toolBox
    private IApiModel _apiModel
    private IPlacementManager _placementManager
    private IGlsRestApiUtils _glsRestApiUtils
    private IClaritySample _claritySample
    private IClarityStep _clarityStep
    private IClarityConfiguration _clarityConfiguration

    public SampleStateManagement(Clarity clarityCredentials, IToolBox toolBox, IApiModel apiModel, IPlacementManager placementManager,
                                 IGlsRestApiUtils glsRestApiUtils, IClaritySample claritySample, IClarityStep clarityStep, IClarityConfiguration clarityConfiguration)
    {
        _clarityConfiguration = clarityConfiguration
        _clarityStep = clarityStep
        _claritySample = claritySample
        _placementManager = placementManager
        _clarityCredentials = clarityCredentials
        _toolBox = toolBox
        _apiModel = apiModel
        _glsRestApiUtils = glsRestApiUtils
    }

    public String[] GetNextStep(String stepUri) {
        def arr = new String[2]
        //TODO (DONE) Mark to send me code to remove hard coding (change need in Clarity API)
        //1. get the configuration uri for current step and httpGET -->http://172.23.8.161:8080/api/v2/steps/24-107673   107877
        //2. Dig out the next step uri of the transition element plus name --> http://172.23.8.161:8080/api/v2/configuration/protocols/251/steps/301
        //3. Find next step by sequence number or name --> http://172.23.8.161:8080/api/v2/configuration/workflows/304 (how to get 304 -Mark to let me know --hard code for now change coming)
        //See here:
        //http://172.23.8.161:8080/api/v2/configuration/workflows/304
        if(RobotMode == RobotMode.NORMALIZATION) {
            arr = _clarityConfiguration.NextStepNormalization()
        } else { //EXTRACTION
            arr = _clarityConfiguration.NextStepExtraction(Workflow)
        }
        return arr
    }

    public void AssignNextStage(String processUri) {
        String[] step = GetNextStep(processUri);
        def builder = new StreamingMarkupBuilder()
        def stageUri = step[1]

        def assignToStage = builder.bind
                {
                    mkp.xmlDeclaration()
                    mkp.declareNamespace(rt: 'http://genologics.com/ri/routing')
                    'rt:routing' {
                        'assign'('stage-uri': stageUri) {
                            for(ia in InputArtifacts) {
                                def resultArtifactURI = ""
                                if(_claritySample.IsControl(ia.LimsId)){ //control
                                    //cannot stage blank
                                    resultArtifactURI = "No Control"
                                }
                                if (resultArtifactURI == ""){ //not control
                                    resultArtifactURI = "${_clarityCredentials.limsHost}/artifacts/${ia.LimsId}"
                                    'artifact'('uri': resultArtifactURI)
                                }
                            }
                        }
                    }
                }

        def uri = _clarityCredentials.limsHost + "/route/artifacts/"
        def assignmentNode = _glsRestApiUtils.xmlStringToNode(assignToStage.toString())
        def assignmentNodeResult = _apiModel.Post(assignmentNode, uri, "AssignNextStage")
    }

    public String StartNextStep(String processUri) {
        String[] step = GetNextStep(processUri);
        def builder = new StreamingMarkupBuilder()
        def stepUri = step[0]

        //https://genologics.zendesk.com/entries/68573603-Starting-a-protocol-step-via-the-API
        def startNextStep = builder.bind
                {
                    mkp.xmlDeclaration()
                    mkp.declareNamespace(tmp: 'http://genologics.com/ri/step')
                    'tmp:step-creation' {
                        'configuration'('uri': stepUri)
                        'container-type'('Chemagic 96 well sample plate')
                        'inputs' {
                            for(ia in InputArtifacts) {
                                def countOfReplicates = InputArtifacts.count { it.LimsId == ia.LimsId }
                                def resultArtifactURI = ""
                                if(_claritySample.IsControl(ia.LimsId)){ //control
                                    //get control-type id
                                    def controlTypeId = limsIdFirstPart.substring(0, limsIdFirstPart.size() - 1)
                                    resultArtifactURI = "${_clarityCredentials.limsHost}/controltypes/${controlTypeId}"
                                    'input'('control-type-uri': resultArtifactURI, 'replicates': countOfReplicates)
                                }
                                if (resultArtifactURI == ""){ //not control
                                    resultArtifactURI = "${_clarityCredentials.limsHost}/artifacts/${ia.LimsId}"
                                    'input'('uri': resultArtifactURI, 'replicates': countOfReplicates)
                                }
                            }
                        }
                    }
                }

        def uri = _clarityCredentials.limsHost + "/steps/"
        def nextStepNode = _glsRestApiUtils.xmlStringToNode(startNextStep.toString())
        def nextStepNodeResponse = _apiModel.Post(nextStepNode, uri, "StartNextStep")
        if(nextStepNodeResponse != null) {
            _placementManager.PlaceAnalytesInNewPlate(nextStepNodeResponse, RobotMode, InputArtifacts)
            return nextStepNodeResponse.@uri //current step
        }
        return ""
    }

    public String StartNextStepWithReplicates(String processUri, int replicates) {
        String[] step = GetNextStep(processUri);
        def builder = new StreamingMarkupBuilder()
        def stepUri = step[0]

        //https://genologics.zendesk.com/entries/68573603-Starting-a-protocol-step-via-the-API
        def startNextStep = builder.bind
                {
                    mkp.xmlDeclaration()
                    mkp.declareNamespace(tmp: 'http://genologics.com/ri/step')
                    'tmp:step-creation' {
                        'configuration'('uri': stepUri)
                        'container-type'('Chemagic 96 well sample plate')
                        'inputs' {
                            for(ia in InputArtifacts) {
                                def resultArtifactURI = "${_clarityCredentials.limsHost}/artifacts/${ia.LimsId}"
                                'input'('uri': resultArtifactURI, 'replicates': replicates)
                            }
                        }
                    }
                }

        def uri = _clarityCredentials.limsHost + "/steps/"
        def nextStepNode = _glsRestApiUtils.xmlStringToNode(startNextStep.toString())
        def nextStepNodeResponse = _apiModel.Post(nextStepNode, uri, "StartNextStep")
        if(nextStepNodeResponse != null) {
            _placementManager.PlaceAnalytesInNewPlate(nextStepNodeResponse, RobotMode, InputArtifacts)
            return nextStepNodeResponse.@uri //current step
        }
        return ""
    }

    public void AdvanceNextInternalStep(currentStep) //next screen i.e. record details
    {
        def stepConfig = _apiModel.Get(currentStep, "AdvanceNextInternalStep")
        CsLogger.Debug("stepConfig: ${_glsRestApiUtils.nodeToXmlString(stepConfig)}")
        def advancedStep = _apiModel.Post(stepConfig, currentStep + "/advance/", "AdvanceNextInternalStep")
        CsLogger.Debug("advancedStep: ${_glsRestApiUtils.nodeToXmlString(advancedStep)}")

        int i = 0
    }

    public void AssignNextStep(String currentStep, String action) //as in next step in protocol, or redo step or manager review etc.
    {
        try {
            def stepNodeResponse = _apiModel.Get(currentStep, "currentStep")
            SetNextAction(stepNodeResponse.@limsid, action)
        } catch (Exception e) {
            int i = 0
        }

    }

    public void AssignNextStepForNormalizationPlates(String currentStep)
    {
        def stepNodeResponse = _apiModel.Get(currentStep, "AssignNextStepForNormalizationPlates")
        def placementNode = _clarityStep.GetPlacements(_toolBox.GetIdFromUri(currentStep), "UpdateOutputPlacement")

        //normalized to progress through workflow
        def container1 = _toolBox.GetIdFromUri(placementNode.value()[2].value().@uri[0])
        SetNextActionByPlate(stepNodeResponse, container1, "complete")

        try {
            //neat aliquot for storage
            def container2 = _toolBox.GetIdFromUri(placementNode.value()[2].value().@uri[1])
            SetNextActionByPlate(stepNodeResponse, container2, "remove") //tried store but only leaves in current step
        } catch (Exception e){
            //no second one
        }
    }


    public void SetNextAction(String stepUri, String action) {

        def builder = new StreamingMarkupBuilder()
        builder.encoding = "UTF-8"
        def stepLimsId = stepUri

        //steps
        def actionsURI = "${_clarityCredentials.limsHost}/steps/${stepLimsId}/actions"
        def actionsNode = _apiModel.Get(actionsURI, "SetNextAction")

        def nextActionList = actionsNode.'next-actions'[0].'next-action' //returns list

        //placements
        for (n in nextActionList)  //need to set next action
        {
            def nextAction = builder.bind
                    {
                        mkp.xmlDeclaration()
                        mkp.declareNamespace(na: 'http://genologics.com/ri/next-action')
                        'next-action'('artifact-uri': n.'@artifact-uri', 'action': action)
                    }

            def list = actionsNode.'next-actions'[0]
            list.remove(list.find { it.'@artifact-uri' == n.'@artifact-uri' })
            list.append(_glsRestApiUtils.xmlStringToNode(nextAction.toString()))
        }
        def nextActionResponse = _apiModel.Put(actionsNode, actionsURI, "SetNextAction")
        int i = 0
    }

    public void SetNextActionAsStep(String stepUri, String nextStepUri) {
        def builder = new StreamingMarkupBuilder()
        builder.encoding = "UTF-8"
        def stepLimsId = stepUri

        //steps
        def actionsURI = "${_clarityCredentials.limsHost}/steps/${stepLimsId}/actions"
        def actionsNode = _apiModel.Get(actionsURI, "SetNextAction")

        def nextActionList = actionsNode.'next-actions'[0].'next-action' //returns list

        //placements
        for (n in nextActionList)  //need to set next action
        {
            def nextAction = builder.bind
                    {
                        mkp.xmlDeclaration()
                        mkp.declareNamespace(na: 'http://genologics.com/ri/next-action')
                        'next-action'('artifact-uri': n.'@artifact-uri', 'action': 'nextstep', 'step-uri': nextStepUri)
                    }

            def list = actionsNode.'next-actions'[0]
            list.remove(list.find { it.'@artifact-uri' == n.'@artifact-uri' })
            list.append(_glsRestApiUtils.xmlStringToNode(nextAction.toString()))
        }
        def nextActionResponse = _apiModel.Put(actionsNode, actionsURI, "SetNextAction")
        CsLogger.Debug("SetNextActionAsStep: " + _glsRestApiUtils.nodeToXmlString(nextActionResponse))
    }

    public void SetNextActionByPlate(Node step, String plateName, String action) {

        def builder = new StreamingMarkupBuilder()
        builder.encoding = "UTF-8"
        def stepLimsId = step.@limsid//"24-107675"

        //steps
        def actionsURI = "${_clarityCredentials.limsHost}/steps/${stepLimsId}/actions"
        def actionsNode = _apiModel.Get(actionsURI, "SetNextAction")

        def nextActionList = actionsNode.'next-actions'[0].'next-action' //returns list

        //placements
        for (n in nextActionList)  //need to set next action
        {
            Node analyteNode = _apiModel.Get(n.@'artifact-uri', "SampleStateManagement/SetNextAction")
            def containerName = analyteNode.value()[5].value()[0].@limsid
            if(containerName == plateName) {
                def nextAction = builder.bind
                        {
                            mkp.xmlDeclaration()
                            mkp.declareNamespace(na: 'http://genologics.com/ri/next-action')
                            'next-action'('artifact-uri': n.'@artifact-uri', 'action': action)
                        }

                def list = actionsNode.'next-actions'[0]
                list.remove(list.find { it.'@artifact-uri' == n.'@artifact-uri' })
                list.append(_glsRestApiUtils.xmlStringToNode(nextAction.toString()))

                def nextActionResponse = _apiModel.Put(actionsNode, actionsURI, "SetNextAction")
            }
        }
    }


}
