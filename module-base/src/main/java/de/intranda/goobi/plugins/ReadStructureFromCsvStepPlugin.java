package de.intranda.goobi.plugins;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.file.DirectoryStreamFilter;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class ReadStructureFromCsvStepPlugin implements IStepPluginVersion2 {

	@Getter
	private String title = "intranda_step_read_structure_from_csv";
	@Getter
	private Step step;
	private SubnodeConfiguration myconfig;
	private String idColumn;
	private String paginationColumn;
	private String fileColumn;
	private String fileMimeType;
	private String fileExtension;
	private String structureType;
	private String returnPath;

	@Override
	public void initialize(Step step, String returnPath) {
		this.returnPath = returnPath;
		this.step = step;

		// read parameters from correct block in configuration file
		myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
		structureType = myconfig.getString("structureTypes", "Chapter");
		idColumn = myconfig.getString("idColumn");
		paginationColumn = myconfig.getString("paginationColumn");
		fileColumn = myconfig.getString("fileColumn");
		fileMimeType = myconfig.getString("fileMimeType");
		fileExtension = myconfig.getString("fileExtension");
		log.info("ReadStructureFromCsv step plugin initialized");
	}

	@Override
	public PluginGuiType getPluginGuiType() {
		return PluginGuiType.NONE;
	}

	@Override
	public String getPagePath() {
		return "/uii/plugin_step_read_structure_from_csv.xhtml";
	}

	@Override
	public PluginType getType() {
		return PluginType.Step;
	}

	@Override
	public String cancel() {
		return "/uii" + returnPath;
	}

	@Override
	public String finish() {
		return "/uii" + returnPath;
	}

	@Override
	public int getInterfaceVersion() {
		return 0;
	}

	@Override
	public HashMap<String, StepReturnValue> validate() {
		return null;
	}

	@Override
	public boolean execute() {
		PluginReturnValue ret = run();
		return ret != PluginReturnValue.ERROR;
	}

	@Override
	public PluginReturnValue run() {
		boolean successful = true;
		try {
			// get CSV file in master folder
			DirectoryStream.Filter<Path> csvFilter = new DirectoryStreamFilter(
					FileFilterUtils.suffixFileFilter(".csv", IOCase.INSENSITIVE));			
			List<Path> files = StorageProvider.getInstance().listFiles(step.getProzess().getImagesOrigDirectory(false), csvFilter);
			if (files.size() != 1) {
				Helper.addMessageToProcessJournal(step.getProzess().getId(), LogType.ERROR,
						"No CSV file or multiple CSV files found in master folder");
				return PluginReturnValue.ERROR;
			} 

			// read METS file
			Fileformat ff = step.getProzess().readMetadataFile();
            Prefs prefs = step.getProzess().getRegelsatz().getPreferences();
            DocStruct ds = ff.getDigitalDocument().getLogicalDocStruct();
            if (ds.getType().isAnchor()) {
                ds = ds.getAllChildren().get(0);
            }
                
			// read CSV file to collect lines for structure elements
			Reader in = new FileReader(files.getFirst().toString());
			Iterable<CSVRecord> records = CSVFormat.RFC4180.builder()
			  .setHeader()
			  .setSkipHeaderRecord(true)
			  .get()
			  .parse(in);
			
			String previousId = null;
			List<CSVRecord> lines = new ArrayList<>();
			for (CSVRecord line : records) {
				String id = line.get(idColumn);
			    
				if (!id.equals(previousId)){
					// add previous element with all belonging lines
					if (previousId!=null) {
						createStructureElement(lines, ds, ff, prefs);
					}
					previousId = id;
					lines = new ArrayList<>();
				}
			    lines.add(line);
			}
			// add last element with all belonging lines
			createStructureElement(lines, ds, ff, prefs);	

			// write METS file and cleanup
			step.getProzess().writeMetadataFile(ff);
			StorageProvider.getInstance().createDirectories(Path.of(step.getProzess().getImportDirectory()));
			StorageProvider.getInstance().move(files.getFirst(), Path.of(step.getProzess().getImportDirectory(), files.getFirst().getFileName().toString()));
			
		} catch (IOException | SwapException | DAOException | ReadException | PreferencesException | TypeNotAllowedForParentException | MetadataTypeNotAllowedException | DocStructHasNoTypeException | TypeNotAllowedAsChildException | WriteException e) {
			Helper.addMessageToProcessJournal(step.getProzess().getId(), LogType.ERROR,
					"Exception occured while reading folder content and CSV analysis: " + e.getMessage());
			return PluginReturnValue.ERROR;
		}

		log.info("ReadStructureFromCsv step plugin executed");
		if (!successful) {
			return PluginReturnValue.ERROR;
		}
		return PluginReturnValue.FINISH;
	}
	
	/**
	 * create a structure element for all lines of an object
	 * 
	 * @param type
	 * @param lines
	 * @throws PreferencesException 
	 * @throws TypeNotAllowedForParentException 
	 * @throws DocStructHasNoTypeException 
	 * @throws MetadataTypeNotAllowedException 
	 * @throws TypeNotAllowedAsChildException 
	 */
	private void createStructureElement(List<CSVRecord> lines, DocStruct parent, Fileformat ff, Prefs prefs) throws TypeNotAllowedForParentException, PreferencesException, MetadataTypeNotAllowedException, DocStructHasNoTypeException, TypeNotAllowedAsChildException {
		DocStruct physical = ff.getDigitalDocument().getPhysicalDocStruct();
		DocStruct ds = ff.getDigitalDocument().createDocStruct(prefs.getDocStrctTypeByName(structureType));

        List<HierarchicalConfiguration> columnNodes = myconfig.configurationsAt("column");
        for (HierarchicalConfiguration node : columnNodes) {
            String header  = node.getString("@header");
            String ruleset = node.getString("@ruleset");
            boolean person = node.getBoolean("@person", false);
            String separator = node.getString("@separator", ",");
            
            if (!person) {
            	addMetadata(ruleset, lines.getFirst().get(header), prefs, ds);            	
            } else {
            	addPerson(ruleset, lines.getFirst().get(header), prefs, ds, separator);            	
            }
        }
        
        // add pages
        for (CSVRecord line : lines) {
			DocStruct dsPage = ff.getDigitalDocument().createDocStruct(prefs.getDocStrctTypeByName("page"));
            physical.addChild(dsPage);
            addMetadata("physPageNumber", String.valueOf(physical.getAllChildren().size()), prefs, dsPage);
            addMetadata("logicalPageNumber", line.get(paginationColumn), prefs, dsPage);
            parent.addReferenceTo(dsPage, "logical_physical");
            ds.addReferenceTo(dsPage, "logical_physical");
            
            ContentFile cf = new ContentFile();
            String file = line.get(fileColumn);
            cf.setMimetype(fileMimeType);
            int extensionIndex = file.lastIndexOf(".");
            cf.setLocation("file://" + file.substring(0, extensionIndex) + fileExtension);
            dsPage.addContentFile(cf);
			
		}
		
		parent.addChild(ds);
	}

	/**
	 * simple helper to create a regular metadata
	 * @param type
	 * @param value
	 * @param prefs
	 * @param ds
	 * @throws MetadataTypeNotAllowedException
	 */
	private void addMetadata(String type, String value, Prefs prefs, DocStruct ds) throws MetadataTypeNotAllowedException {
		Metadata m = new Metadata(prefs.getMetadataTypeByName(type));
        m.setValue(value);
        ds.addMetadata(m);
	}
	
	/**
	 * simple helper to create a person
	 * @param type
	 * @param value
	 * @param prefs
	 * @param ds
	 * @throws MetadataTypeNotAllowedException
	 */
	private void addPerson(String type, String fullname, Prefs prefs, DocStruct ds, String separator) throws MetadataTypeNotAllowedException {
		Person p = new Person(prefs.getMetadataTypeByName(type));
        p.setLastname(fullname.substring(0, fullname.indexOf(separator)).trim());
        p.setFirstname(fullname.substring(fullname.indexOf(separator) + 1).trim());
        ds.addPerson(p);
	}

}
