package com.exlibris.dps.delivery.vpp.bookReader;

import gov.loc.mets.MetsType.FileSec.FileGrp;
import gov.loc.mets.StructMapType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import javax.servlet.http.HttpServletRequest;

import com.exlibris.core.infra.common.cache.SessionUtils;
import com.exlibris.core.infra.common.exceptions.logging.ExLogger;
import com.exlibris.core.infra.svc.api.CodeTablesResourceBundle;
import com.exlibris.core.sdk.consts.Enum;
import com.exlibris.core.sdk.formatting.DublinCore;
import com.exlibris.digitool.common.dnx.DnxDocument;
import com.exlibris.digitool.common.dnx.DnxDocumentHelper;
import com.exlibris.digitool.common.dnx.DnxDocumentHelper.SignificantProperties;
import com.exlibris.digitool.config.DeliveryConstants;
import com.exlibris.dps.sdk.access.Access;
import com.exlibris.dps.sdk.access.AccessException;
import com.exlibris.dps.sdk.delivery.AbstractViewerPreProcessor;
import com.exlibris.dps.sdk.delivery.SmartFilePath;
import com.exlibris.dps.sdk.deposit.IEParser;

public class BookReaderViewerPreProcessor extends AbstractViewerPreProcessor{

	public enum repType {JPEG, JPG, PDF, JP2, TIFF, PNG};
	private List<String> filePids = new ArrayList<String>();
	private String pid = null;
	private String repPid = null;
	private repType type = null;
	private String title = "";
	private String ext = "";
	private DnxDocumentHelper fileDocumentHelper = null;
	private String progressBarMessage = null;
	private static final ExLogger logger = ExLogger.getExLogger(BookReaderViewerPreProcessor.class);
	Access access;

	//This method will be called by the delivery framework before the call for the execute Method
	@Override
	public void init(DnxDocument dnx, Map<String, String> viewContext, HttpServletRequest request, String dvs,String ieParentId, String repParentId)
			throws AccessException {
		super.init(dnx, viewContext, request, dvs, ieParentId, repParentId);
		this.pid = getPid();
		IEParser ieParser;
		try {
			ieParser = getAccess().getIEByDVS(dvs);
			FileGrp[] repList = ieParser.getFileGrpArray();
			StructMapType[] structMapTypeArray = ieParser.getStructMapsByFileGrpId(repList[0].getID());
			
			repPid = repList[0].getID();
			for(StructMapType structMapType:structMapTypeArray){
				filePids = ieParser.getFilesArray(structMapType);
				if(structMapType.getTYPE().equals(Enum.StructMapType.LOGICAL.name())){
					break;
				}
			}
			DublinCore dc = ieParser.getIeDublinCore();
			this.title = dc.getTitle();
			DnxDocument firstFileDnx = getAccess().getFileInfoByDVS(dvs, filePids.get(0));
			fileDocumentHelper = new DnxDocumentHelper(firstFileDnx);
			ext = fileDocumentHelper.getGeneralFileCharacteristics().getFileExtension();
			if(ext.toUpperCase().equals(repType.JPEG.name())){
				type = repType.JPEG;
			}else if(ext.toUpperCase().equals(repType.JPG.name())){
				type = repType.JPG;
			}else if(ext.toUpperCase().equals(repType.JP2.name())){
				type = repType.JP2;
			}else if(ext.toUpperCase().equals(repType.PNG.name())){
				type = repType.PNG;
			}else{
				logger.error("Error In Book Reader VPP - The viewer doesn't support the following ext:" + ext.toUpperCase(), pid);
				throw new Exception();
			}

		} catch (Exception e) {
			logger.error("Error In Book Reader VPP - cannot retreive the files to view", e, pid);
			throw new AccessException();
		}
	}

	public boolean runASync(){
		return true;
	}

	//Does the pre-viewer processing tasks.
	public void execute() throws Exception {
		Map<String, Object> paramMap = getAccess().getViewerDataByDVS(getDvs()).getParameters();

		//moved from switch-case to else-if because of plugin issues related to including this ENUM class
		if(type != null && (type.equals(repType.JPEG) || type.equals(repType.JPG) || type.equals(repType.PNG))){
			prepareImageFiles();
		} else {
			logger.warn("Book reader viewer pre processor doesn't support type: " + type + ", PID: " + pid);
		}

		// add params to session
        paramMap.put("ie_dvs", getDvs());
        paramMap.put("ie_pid", pid);
        paramMap.put("rep_pid", repPid);
        paramMap.put("extension", ext.toLowerCase());
        paramMap.put("num_of_pages", String.valueOf(filePids.size()));
        for(SignificantProperties significantPropertiess:fileDocumentHelper.getSignificantPropertiess()){
        	if(significantPropertiess.getSignificantPropertiesType().equals(DeliveryConstants.IMAGE_HEIGHT)){
        		paramMap.put("image_height", significantPropertiess.getSignificantPropertiesValue());
        	}
        	if(significantPropertiess.getSignificantPropertiesType().equals(DeliveryConstants.IMAGE_WIDTH)){
        		paramMap.put("image_width", significantPropertiess.getSignificantPropertiesValue());
        	}
        }
        if(paramMap.get("image_height") == null){paramMap.put("image_height", "1200");}//default height
        if(paramMap.get("image_width") == null){paramMap.put("image_width", "800");}//default width
        paramMap.put("ie_title", title);
        getAccess().setParametersByDVS(getDvs(), paramMap);
        getAccess().updateProgressBar(getDvs(), "", 100);
	}

	private void prepareImageFiles() throws Exception{
		//export + rename
		String filePath = "";
		for(int index=0;index<filePids.size();index++){
			filePath = getAccess().exportFileStream(filePids.get(index), BookReaderViewerPreProcessor.class.getSimpleName(), ieParentId, repDirName, repPid + File.separator +(index));
			updateProgressBar(index);
		}
		String directoryPath = filePath.substring(0, filePath.lastIndexOf(String.valueOf(filePids.size() - 1)));
		getAccess().setFilePathByDVS(getDvs(), new SmartFilePath(directoryPath), repPid);
	}

	private void updateProgressBar(int index) throws Exception{
		if((index % 10) == 0){
			if(progressBarMessage == null){
				Locale locale = new Locale(SessionUtils.getSessionLanguage());
				ResourceBundle resourceBundle = CodeTablesResourceBundle.getDefaultBundle(locale);
				progressBarMessage = resourceBundle.getString("delivery.progressBar.bookReaderMessage");
			}
			getAccess().updateProgressBar(getDvs(), progressBarMessage, Integer.valueOf((index*100)/filePids.size()));
		}
	}

}
