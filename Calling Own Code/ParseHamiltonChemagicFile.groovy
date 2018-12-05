package org.petermac.clarity.hamiltonChemagic

import org.petermac.clarity.entities.ChemagicFileItem
import org.petermac.clarity.entities.Enums
import org.petermac.clarity.tools.IFileManagement
import org.petermac.clarity.tools.ISampleStateManagement
import org.petermac.clarity.tools.FileManagement
import org.petermac.clarity.tools.IToolBox
import org.petermac.clarity.tools.SampleStateManagement
import org.petermac.logging.CsLogger
import org.petermac.util.Clarity

/**
 * Created by reid gareth on 16/10/2014.
 */

public class ParseHamiltonChemagicFile //implements IParseHamiltonChemagicFile
{

    private Clarity _clarityCredentials
    private IToolBox _toolBox
    private ISampleStateManagement _sampleStateManagement
    private IFileManagement _fileManagement

    public ParseHamiltonChemagicFile(Clarity clarityCredentials, IToolBox toolBox, ISampleStateManagement sampleStateManagement,
                                     IFileManagement fileManagement)
    {
        _fileManagement = fileManagement
        _sampleStateManagement = sampleStateManagement
        _clarityCredentials = clarityCredentials
        _toolBox = toolBox
    }
    public void Execute(args)  //for running from services i.e. command line not from code
    {
        def cli = new CliBuilder(usage: "groovy c.groovy -h HOSTNAME -u USERNAME -p PASSWORD -c CONTAINERLIMSID")
         cli.with
                {
                    t(argName: 'tool', longOpt: 'tool', required: true, args: 1, 'tool to execute')
                    u(argName: 'username', longOpt: 'username', required: true, args: 1, 'LIMS username (Required)')
                    p(argName: 'password', longOpt: 'password', required: true, args: 1, 'LIMS password (Required)')
                    l(longOpt: 'lims', args: 1, required: true, "LIMS Environment to use (prod|test)")
                    a(longOpt: 'processUri', args: 1, required: true, "Process Uri needed")
                    f(longOpt: 'fileLimsId', args: 1, required: true, "fileLimsId")
                    w(longOpt: 'workflow', args: 1, required: false, "workflow")
                    d(longOpt: 'debug', 'Turn on debug logging')
                }

        def opt = cli.parse(args)
        println("user: ${opt.u} password: ${opt.p} env: ${opt.l} processUri: ${opt.a} fileId: ${opt.f}")
        CsLogger.Info("user: ${opt.u} password: ${opt.p} env: ${opt.l} processUri: ${opt.a} fileId: ${opt.f}")
        if (!opt) {
            System.exit(-1)
        }

        //************ CURRENT OPEN TISSUE DEBUGGER LINE FOR BLANK THAT WONT PLATE -t parseChemagic -u apiuser -p 1qaz@WSX -l prod -f 92-26908 -a http://172.23.8.186:8080/api/v2/processes/24-5536
        def fileLimsId = opt.f

        def processUri = opt.a
        def chemagicFileItemsFromFile = parseFile(fileLimsId)
        _sampleStateManagement.InputArtifacts = chemagicFileItemsFromFile
        _sampleStateManagement.RobotMode = Enums.RobotMode.EXTRACTION
        if(opt.w == "blood"){
            _sampleStateManagement.Workflow = Enums.Workflow.BLOOD
        }else {
            _sampleStateManagement.Workflow = Enums.Workflow.TISSUE
        }

        _sampleStateManagement.AssignNextStage(processUri)

        def currentStep = _sampleStateManagement.StartNextStep(processUri)
        _sampleStateManagement.AdvanceNextInternalStep(currentStep)//to record details
        _sampleStateManagement.AdvanceNextInternalStep(currentStep)//to assign next steps
        _sampleStateManagement.AssignNextStep(currentStep, "complete") //set next step
        _sampleStateManagement.AdvanceNextInternalStep(currentStep)//after set next step...complete
    }


    private List<ChemagicFileItem> parseFile(String fileLimsId) {

        try {
            File localFile = _fileManagement.GetFileFromStepUri(fileLimsId)
            List<ChemagicFileItem> chemagicFileItemsFromFile = new LinkedList<ChemagicFileItem>()
            int i = 0

            localFile.eachLine { line ->
                CsLogger.Debug("Method: parseFile, file contains: ${line}")
                if (i > 0) {//ignore header
                    //HEADER:::WellBarcode, PlateBarcode, SampleName, LimsId
                    def sampleArr = line.split(",")
                    def chemagicFileItem = new ChemagicFileItem()
                    //DATA:::1234,A1,121244,14M5097,ADM584A6PA1
                    chemagicFileItem.PlateBarcode = sampleArr[4].replace("\"", "")
                    chemagicFileItem.Position = sampleArr[2].replace("\"", "")
                    chemagicFileItem.WellBarcode = sampleArr[0].replace("\"", "")
                    chemagicFileItem.SampleName = sampleArr[3].replace("\"", "")
                    chemagicFileItem.LimsId = sampleArr[1].replace("\"", "")

                    chemagicFileItemsFromFile.add(chemagicFileItem)
                }
                i++
            }
            return chemagicFileItemsFromFile
            } catch(Exception e) {
            int i = 0
            return ""

                }
    }
}

