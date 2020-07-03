/*
 * Vhdl RuleChecker (Vhdl-rc) plugin for Sonarqube & Zamiacad
 * Copyright (C) 2019 Maxime Facquet
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.linty.sonar.plugins.vhdlrc;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import javax.xml.stream.XMLStreamException;

import org.fest.util.VisibleForTesting;
import org.sonar.api.batch.fs.FilePredicates;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import com.linty.sonar.plugins.vhdlrc.issues.ExternalReportProvider;
import com.linty.sonar.plugins.vhdlrc.issues.Issue;
import com.linty.sonar.plugins.vhdlrc.issues.ReportXmlParser;
import com.linty.sonar.zamia.BuildPathMaker;
import com.linty.sonar.zamia.ZamiaRunner;

public class VhdlRcSensor implements Sensor {
	public static final String SCANNER_HOME_KEY ="sonar.vhdlrc.scanner.home";
	public static final String      PROJECT_DIR = "rc/Data/workspace/project";
	public static final String   REPORTING_PATH = PROJECT_DIR + "/rule_checker/reporting/rule";
	public static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().startsWith("windows");
	public static final String RC_SYNTH_REPORT_PATH = IS_WINDOWS ? ".\\report_" : "./report_";
	private static final Logger LOG = Loggers.get(VhdlRcSensor.class);
	private static List<String> unfoundFiles = new ArrayList<>();

	@Override
	public void describe(SensorDescriptor descriptor) {
		descriptor
		.name("Import of RuleChecker Xml Reports")
		.onlyOnLanguage(Vhdl.KEY)
		.name("vhdlRcSensor")
		.onlyWhenConfiguration(conf -> conf.hasKey(SCANNER_HOME_KEY));
	}

	@Override
	public void execute(SensorContext context) {

		//ZamiaRunner-------------------------------------------------------
		String top=BuildPathMaker.getTopEntities(context.config());
		if(top.isEmpty()) {
			LOG.warn("Vhdlrc anaysis skipped : No defined Top Entity. See " + BuildPathMaker.TOP_ENTITY_KEY);
			LOG.warn("Zamia Issues will still be imported");
		} else {
			ZamiaRunner.run(context); 
		}
		//------------------------------------------------------------------

		if(BuildPathMaker.getAutoexec(context.config())) {			
			String fsmRegex = BuildPathMaker.getFsmRegex(context.config());
			String rcSynth = BuildPathMaker.getRcSynthPath(context.config());
			if(IS_WINDOWS) {
				try {
					Runtime.getRuntime().exec("cmd.exe /c cmd.exe /c ubuntu1804 run "+rcSynth+" "+top+" \""+fsmRegex+"\"").waitFor();
				} catch (IOException | InterruptedException e) {
				}
			}
			else {
				try {
					System.out.println("ubuntu shell");
					String[] cmd = new String[] {"sh","-c","bash "+rcSynth+" "+top+" \""+fsmRegex+"\""};
					System.out.println(executeCommand(cmd));
					//System.out.println("bash "+rcSynth+" "+top+" \""+fsmRegex+"\"");
					//Runtime.getRuntime().exec("bash "+rcSynth+" "+top+" \""+fsmRegex+"\"").waitFor();
				} catch (Exception e) {
					System.out.println("error");
				}
			}

		}

		Path reportsDir = Paths
				.get(context.config()
						.get(SCANNER_HOME_KEY)
						.orElseThrow(() -> new IllegalStateException("vhdlRcSensor should not execute without " + SCANNER_HOME_KEY)))
				.resolve(REPORTING_PATH);
		List<Path> reportFiles = ExternalReportProvider.getReportFiles(reportsDir);
		Path rcSynthReport = Paths.get("./");
		List<Path> rcReportFiles = ExternalReportProvider.getReportFiles(rcSynthReport);
		rcReportFiles.forEach(o->System.out.println(o.toString()));
		rcReportFiles.removeIf(o->!o.toString().startsWith(RC_SYNTH_REPORT_PATH));
		if(!rcReportFiles.isEmpty())
			reportFiles.addAll(rcReportFiles);
		reportFiles.forEach(report -> importReport(report, context));
		unfoundFiles.forEach(s -> LOG.warn("Input file not found : {}. No rc issues will be imported on this file.",s));
	}

	@VisibleForTesting
	protected void importReport(Path reportFile, SensorContext context) {
		try {
			LOG.info("Importing {}", reportFile.getFileName());
			boolean rcSynth=reportFile.toString().startsWith(RC_SYNTH_REPORT_PATH);
			for(Issue issue : ReportXmlParser.getIssues(reportFile)){
				try {
					importIssue(context, issue,rcSynth);
				} catch (RuntimeException e) {
					LOG.warn("Can't import an issue from report {} : {}", reportFile.getFileName(), e.getMessage());
				}  
			}
		} catch (XMLStreamException e) {			
			LOG.error("Error when reading xml report : {}", e.getLocation());
		}  
	}

	private void importIssue(SensorContext context, Issue i, boolean reportFromRcsynth) {
		InputFile inputFile;
		NewIssueLocation issueLocation;
		Path p = i.file();
		Path filePath;
		if (reportFromRcsynth)
			filePath=p;
		else { 
			Path root = Paths.get("./");
			filePath = root.resolve(p.subpath(2, p.getNameCount()));//Zamia adds "./vhdl" to inputFile path in reports
		}
		FilePredicates predicates = context.fileSystem().predicates();
		inputFile = context.fileSystem().inputFile(predicates.hasPath(filePath.toString()));
		if(inputFile == null) {
			if(!unfoundFiles.contains(filePath.toString())){    
				unfoundFiles.add(filePath.toString());
			}
		} else {
			NewIssue ni = context.newIssue()
					.forRule(RuleKey.of("vhdlrc-repository",i.ruleKey()));
			issueLocation = ni.newLocation()
					.on(inputFile)
					.at(inputFile.selectLine(i.line()))
					.message(i.errorMsg());
			ni.at(issueLocation);
			ni.save(); 
		}
	}
	
	public String executeCommand(String[] cmd) {
	    StringBuffer theRun = null;
	    try {
	        Process process = Runtime.getRuntime().exec(cmd);

	        BufferedReader reader = new BufferedReader(
	                new InputStreamReader(process.getInputStream()));
	        int read;
	        char[] buffer = new char[4096];
	        StringBuffer output = new StringBuffer();
	        while ((read = reader.read(buffer)) > 0) {
	            theRun = output.append(buffer, 0, read);
	        }
	        reader.close();
	        process.waitFor();

	    } catch (IOException e) {
	        throw new RuntimeException(e);
	    } catch (InterruptedException e) {
	        throw new RuntimeException(e);
	    }
	        return theRun.toString().trim();
	}

}
