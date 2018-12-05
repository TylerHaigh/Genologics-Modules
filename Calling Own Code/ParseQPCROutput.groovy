package org.petermac.clarity.RTPCR

import org.petermac.clarity.clarityApi.IApiModel
import org.petermac.clarity.clarityApi.IClarityProcess
import org.petermac.clarity.clarityApi.IClaritySample
import org.petermac.clarity.clarityApi.IGlsRestApiUtils
import org.petermac.clarity.entities.ChemagicFileItem
import org.petermac.clarity.entities.ChemagicNormalizationFileItem
import org.petermac.clarity.entities.DnaQuality
import org.petermac.clarity.entities.Enums
import org.petermac.clarity.entities.IDnaQuality
import org.petermac.clarity.tools.IFileManagement
import org.petermac.clarity.tools.IQcManager
import org.petermac.clarity.tools.ISampleStateManagement
import org.petermac.clarity.tools.IToolBox
import org.petermac.logging.CsLogger
import org.petermac.util.Clarity
import org.petermac.util.UdfConfiguration

/**
 * Created by reid gareth on 16/10/2014.
 */

public class ParseQPCRFile //implements IParseHamiltonChemagicFile
{

    private IToolBox _toolBox
    private IApiModel _apiModel
    private IFileManagement _fileManagement
    private IGlsRestApiUtils _glsRestApiUtils
    private IDnaQuality _dnaQuality
    private IClaritySample _claritySample
    private IClarityProcess _clarityProcess
    private IQcManager _qcManager
    private String _mode

    public ParseQPCRFile(IGlsRestApiUtils glsRestApiUtils, IDnaQuality dnaQuality, IClaritySample claritySample,
                         IFileManagement fileManagement, IClarityProcess clarityProcess, IApiModel apiModel,
                         IToolBox toolBox, IQcManager qcManager)
    {
        _qcManager = qcManager
        _toolBox = toolBox
        _apiModel = apiModel
        _clarityProcess = clarityProcess
        _claritySample = claritySample
        _dnaQuality = dnaQuality
        _glsRestApiUtils = glsRestApiUtils
        _fileManagement = fileManagement
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
                    m(longOpt: 'mode', args: 1, required: false, "mode")
                    d(longOpt: 'debug', 'Turn on debug logging')
                }

        def opt = cli.parse(args)
        println("user: ${opt.u} password: ${opt.p} env: ${opt.l} processUri: ${opt.a} fileId: ${opt.f}")
        CsLogger.Info("user: ${opt.u} password: ${opt.p} env: ${opt.l} processUri: ${opt.a} fileId: ${opt.f}")
        if (!opt) {
            System.exit(-1)
        }

        if (opt.m) {
            _mode = opt.m
        }
        def fileLimsId = opt.f

        def processUri = opt.a
        parseFile(fileLimsId)
        CalculateQuality(processUri)
    }

    private void parseFile(String fileLimsId) {
        File localFile = _fileManagement.GetFileFromStepUri(fileLimsId)
        def cpValues = new LinkedList<QPCRValue>()
        int i = 0
        localFile.eachLine { line ->
            CsLogger.Debug("Method: ParseQPCRFile.parseFile, file contains: ${line}")
            if (i > 1) {//ignore headers
                def sampleArr = line.split("\t")
                //gather up values and ids
                if(sampleArr[3] != "" && !sampleArr[3].contains("Sample")) {
                    def limsId = sampleArr[3].replace("\"", "")
                    if (limsId.contains("_")) {
                        limsId = limsId.split()[0]
                    }
                    cpValues.add(new QPCRValue(limsId: limsId, cpValue: sampleArr[4].replace("\"", ""), concentration: sampleArr[5].replace("\"", "")))
                }
                if (sampleArr[3] != "") { //set concentration UDF foir analyte
                    _claritySample.SetSampleUdf(sampleArr[3].replace("\"", ""), "QPCR Concentration", sampleArr[5].replace("\"", ""), true)
                }
            }
            i++
        }
        //cp
        def grouped = cpValues.groupBy { it.limsId }
        grouped.each { groupedCpValues ->
            Float count = 0
            def limsId = groupedCpValues.key
            def mean = 0

            for (value in groupedCpValues.getValue()) {
                if(value.cpValue != "") {
                    mean += Float.parseFloat(value.cpValue)
                    count++
                }
            }
            def meanCp = mean / count //set mean CP value across 3 replicates(UDF)
            _claritySample.SetSampleUdf(limsId, "QPCR CP", meanCp.toString(), true)
        }
        //conc
        def groupedConc = cpValues.groupBy { it.limsId }
        groupedConc.each { groupedCpValues ->
            Float count = 0
            def limsId = groupedCpValues.key
            def meanConc = 0

            for (value in groupedCpValues.getValue()) {
                if (value.concentration != "") {
                    def concentrationString = value.concentration
                    def (number, power) = concentrationString.tokenize("E")
                    def concentrationExp =  Float.parseFloat(number) * Math.pow(10, Float.parseFloat(power))
                    meanConc += concentrationExp
                    count++
                }
            }
            def udfMap = new HashMap<String, String>()
            def meanConcValue = meanConc / count
            if (meanConcValue.toString() != "NaN") { //set mean concentration across 3 replicates (UDF)
                udfMap.put("Mean QPCR Concentration", meanConcValue)
                _toolBox.UpdateAnalyteUdfs(limsId, udfMap)
                //_claritySample.SetAnalyteUdf(limsId, , meanConcValue.toString())
            }

        }
    }

    class QPCRValue {
        String limsId
        String cpValue
        String concentration
    }

    public void CalculateQuality(def processUri){
        def analytes = _clarityProcess.GetFilesPerInputs(_toolBox.GetIdFromUri(processUri))
        def controlCpAnalyte = analytes.find { it.'input'.@uri[0].contains("201C")}
        def controlInputAnalyteUri = controlCpAnalyte.'input'.@uri[0]

        def controlAnalyte = _apiModel.Get(controlInputAnalyteUri, "GenerateNormWorklist.BuildFile")
        def controlSampleLimsId = controlAnalyte.'sample'.@limsid[0]
        def controlCp = _claritySample.GetSampleUdf(controlSampleLimsId, "QPCR CP", false)
        for (a in analytes) {
            //get analyte
            def inputAnalyteUri = a.'input'.@uri[0]
            def analyte = _apiModel.Get(inputAnalyteUri, "GenerateNormWorklist.BuildFile")
            def analyteId = analyte
            //set DNA quality
            def sampleLimsId = analyte.'sample'.@limsid[0]
            def qPcrCp = _claritySample.GetSampleUdf(sampleLimsId, "QPCR CP", false)
            def quality = _dnaQuality.CalculateQuality(qPcrCp, controlCp)
            //get sample neat concentration
            def concentration = _glsRestApiUtils.GetUdfValue(analyte, UdfConfiguration.Analyte.Concentration)
            def volumeToAdd = 4
            if (quality != DnaQuality.DnaType.NA) {
                _claritySample.SetSampleUdf(sampleLimsId, "DNA Quality", quality.toString(), false)
                def delta = Float.parseFloat(qPcrCp) - Float.parseFloat(controlCp)
                _claritySample.SetSampleUdf(sampleLimsId, "Illumina Delta CP", delta.toString(), false)
            }
            if (concentration != "") {
                volumeToAdd = _dnaQuality.CalculateDnaToAdd(concentration)
            }

            _glsRestApiUtils.setUdfValue(analyte, "Recommended DNA", volumeToAdd.toString())
        }
    }
}


