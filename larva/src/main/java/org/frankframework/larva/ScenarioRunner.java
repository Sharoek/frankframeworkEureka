/*
   Copyright 2014-2019 Nationale-Nederlanden, 2020-2024 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.frankframework.larva;

import static org.frankframework.larva.LarvaTool.RESULT_AUTOSAVED;
import static org.frankframework.larva.LarvaTool.RESULT_ERROR;
import static org.frankframework.larva.LarvaTool.RESULT_OK;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.logging.log4j.CloseableThreadContext;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import org.frankframework.configuration.IbisContext;
import org.frankframework.larva.actions.LarvaActionFactory;
import org.frankframework.larva.actions.LarvaApplicationContext;
import org.frankframework.larva.actions.LarvaScenarioAction;
import org.frankframework.util.AppConstants;
import org.frankframework.util.StringUtil;
import org.frankframework.util.XmlEncodingUtils;

@Log4j2
public class ScenarioRunner {

	private static final AtomicLong correlationIdSuffixCounter = new AtomicLong(1);
	private static final String TESTTOOL_CORRELATIONID = "Test Tool correlation id";
	public final List<String> parallelBlacklistDirs;

	private @Getter final AtomicInteger scenariosFailed = new AtomicInteger();
	private @Getter final AtomicInteger scenariosPassed = new AtomicInteger();
	private @Getter final AtomicInteger scenariosAutosaved = new AtomicInteger();
	private @Getter int scenariosTotal;

	private final LarvaTool larvaTool;
	private final IbisContext ibisContext;
	private final TestConfig config;
	private final AppConstants appConstants;
	private boolean evenStep;
	private final int waitBeforeCleanUp;
	private final LarvaLogLevel logLevel;
	private @Setter boolean multipleThreads;
	private final int threads;

	public ScenarioRunner(LarvaTool larvaTool, IbisContext ibisContext, TestConfig config, AppConstants appConstants, int waitBeforeCleanUp, LarvaLogLevel logLevel) {
		this.larvaTool = larvaTool;
		this.ibisContext = ibisContext;
		this.config = config;
		this.appConstants = appConstants;
		this.evenStep = false;
		this.waitBeforeCleanUp = waitBeforeCleanUp;
		this.logLevel = logLevel;
		this.multipleThreads = config.isMultiThreaded();

		String blackListDirs = AppConstants.getInstance().getProperty("larva.parallel.blacklistDirs", "");
		parallelBlacklistDirs = StringUtil.split(blackListDirs);
		log.info("Setting parallel blacklist dirs to: {}", parallelBlacklistDirs);

		threads = AppConstants.getInstance().getInt("larva.parallel.threads", 4);
	}

	public void runScenario(List<File> scenarioConfigurationFiles, String larvaScenarioRootDirectory) {
		scenariosTotal = scenarioConfigurationFiles.size();
		Map<String, List<File>> filesByFolder = groupFilesByFolder(scenarioConfigurationFiles, larvaScenarioRootDirectory);
		log.debug("Found: {} folders", filesByFolder.size());

		List<File> singleThreadedScenarios;
		if (multipleThreads) {
			singleThreadedScenarios = runScenariosMultithreaded(larvaScenarioRootDirectory, filesByFolder);
		} else {
			singleThreadedScenarios = scenarioConfigurationFiles;
		}

		runScenariosSingleThreaded(singleThreadedScenarios, larvaScenarioRootDirectory);
		log.info("Summary Larva run Scenario's: {} passed, {} failed. Total: {}", scenariosPassed, scenariosFailed, scenarioConfigurationFiles.size());
	}

	private List<File> runScenariosMultithreaded(String currentScenariosRootDirectory, Map<String, List<File>> filesByFolder) {
		List<File> singleThreadedScenarios = new ArrayList<>(); // Collect scenarios that should be run single threaded

		// Run each scenario folder in a separate thread
		// Not using a try-with-resources because the default awaitTermination is set on 1 day
		ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads);
		try {
			filesByFolder.forEach((folder, files) -> {
				log.debug("Starting FOLDER: {} - found: {} files", folder, files.size());
				if (parallelBlacklistDirs.contains(folder)) {
					log.debug("Skipping folder because found in parallel blacklist: {}", folder);
					singleThreadedScenarios.addAll(files);
					return;
				}
				executor.submit(() -> files.forEach(file -> runOneFile(file, currentScenariosRootDirectory, false)));
			});
			// Wait for all scenarios to finish
			executor.shutdown();
		} finally {
			try {
				executor.shutdown();
				executor.awaitTermination(15, TimeUnit.MINUTES); // Guessing a max timeout, otherwise we might hang forever
			} catch (InterruptedException e) {
				log.warn("Interrupted while waiting for scenario runner to finish", e);
				Thread.currentThread().interrupt();
			}
		}
		return singleThreadedScenarios;
	}

	private void runScenariosSingleThreaded(List<File> singleThreadedScenarios, String currentScenariosDirectory) {
		if (singleThreadedScenarios.isEmpty()) {
			return;
		}
		try {
			Writer out = (config.isSilent()) ? config.getSilentOut() : config.getOut();
			out.write("<br/><h2>Starting " + singleThreadedScenarios.size() + " Single threaded Scenarios </h2>");
		} catch (IOException ignored) {
			// ignore exception
		}

		singleThreadedScenarios.forEach(file -> runOneFile(file, currentScenariosDirectory, true));
	}

	// Sort property files by folder
	public static Map<String, List<File>> groupFilesByFolder(List<File> scenarioFiles, String currentScenariosRootDirectory) {
		return scenarioFiles.stream()
				.collect(Collectors.groupingBy(scenarioFile -> getScenarioFolder(scenarioFile, currentScenariosRootDirectory)));
	}

	private static String getScenarioFolder(File file, String currentScenariosDirectory) {
		if (currentScenariosDirectory.endsWith(File.separator)) {
			currentScenariosDirectory = currentScenariosDirectory.substring(0, currentScenariosDirectory.length() - 1);
		}
		if (currentScenariosDirectory.equals(file.getParent())) {
			return "";
		}

		File parentFolder = file.getParentFile();
		while (parentFolder != null && !currentScenariosDirectory.equals(parentFolder.getParent())) {
			parentFolder = parentFolder.getParentFile();
		}
		if (parentFolder == null) {
			return file.getParent();
		}
		return parentFolder.getName();
	}

	/**
	 * @param scenarioConfigurationFile full path to the `.properties` configuration file
	 * @param larvaScenariosRootDirectory the root directory of all larva scenarios
	 * @param flushLogsForEveryScenarioStep if true, the log will be flushed after every scenario step
	 */
	public int runOneFile(File scenarioConfigurationFile, String larvaScenariosRootDirectory, boolean flushLogsForEveryScenarioStep) {
		// increment suffix for each scenario
		int scenarioPassed = RESULT_ERROR;

		LarvaApplicationContext applicationContext = null;
		String scenarioFolderName = getScenarioFolder(scenarioConfigurationFile, larvaScenariosRootDirectory);
		log.info("Running scenario [{}]", scenarioFolderName);
		try (CloseableThreadContext.Instance ctc = CloseableThreadContext.put("scenario", scenarioFolderName)) {

			String scenarioDirectory = scenarioConfigurationFile.getParentFile().getAbsolutePath() + File.separator;
			String longName = scenarioConfigurationFile.getAbsolutePath();
			String shortName = longName.substring(larvaScenariosRootDirectory.length() - 1, longName.length() - ".properties".length());

			// This is far for optimal, but without refactoring the whole LarvaTool, this is the quick and dirty way to do it
			applicationContext = new LarvaApplicationContext(ibisContext, scenarioDirectory);

			larvaTool.debugMessage("Read property file " + scenarioConfigurationFile.getName());
			Properties properties = larvaTool.readProperties(appConstants, scenarioConfigurationFile);
			String scenarioDescription = properties.getProperty("scenario.description");

			larvaTool.debugMessage("Open actions");

			LarvaActionFactory actionFactory = new LarvaActionFactory(larvaTool);

			String correlationId = TESTTOOL_CORRELATIONID + "(" + correlationIdSuffixCounter.getAndIncrement() + ")";
			Map<String, LarvaScenarioAction> larvaActions = actionFactory.createLarvaActions(properties, applicationContext, correlationId);
			applicationContext.configure();
			applicationContext.start();

			// Start the scenario
			StringBuilder output = new StringBuilder();
			if (!config.isSilent() && (logLevel.shouldLog(LarvaLogLevel.SCENARIO_PASSED_FAILED))) {
				output.append("<br/><br/><div class='scenario'>");
			}

			larvaTool.debugMessage("Read steps from property file");
			List<String> stepList = getSteps(properties);
			if (larvaActions != null) {
				larvaTool.debugMessage("Execute steps");
				boolean allStepsPassed = true;
				boolean autoSaved = false;
				Iterator<String> steps = stepList.iterator();
				while (allStepsPassed && steps.hasNext()) {
					if (evenStep) {
						output.append("<div class='even'>");
						evenStep = false;
					} else {
						output.append("<div class='odd'>");
						evenStep = true;
					}
					String step = steps.next();
					String stepDisplayName = shortName + " - " + step + " - " + properties.get(step);
					larvaTool.debugMessage("Execute step '" + stepDisplayName + "'");
					LocalTime start = LocalTime.now();
					int stepPassed = larvaTool.executeStep(step, properties, stepDisplayName, larvaActions, correlationId);
					LocalTime end = LocalTime.now();
					if (stepPassed == RESULT_OK) {
						if (logLevel.shouldLog(LarvaLogLevel.STEP_PASSED_FAILED)) output.append(stepPassedMessage("Step '" + stepDisplayName + "' passed."));
					} else if (stepPassed == RESULT_AUTOSAVED) {
						if (logLevel.shouldLog(LarvaLogLevel.STEP_PASSED_FAILED))
							output.append(stepAutosavedMessage("Step '" + stepDisplayName + "' passed after autosave."));
						autoSaved = true;
					} else {
						if (logLevel.shouldLog(LarvaLogLevel.STEP_PASSED_FAILED)) output.append(stepFailedMessage("Step '" + stepDisplayName + "' failed."));
						allStepsPassed = false;
					}
					if (logLevel.shouldLog(LarvaLogLevel.DEBUG)) {
						output.append(" Test Duration: " + start.until(end, ChronoUnit.MILLIS) + " ms");
					}
					output.append("</div>");
					if (flushLogsForEveryScenarioStep) {
						larvaTool.writeHtml(output.toString(), true);
						config.flushWriters();
						output.setLength(0);
					}
				}
				if (allStepsPassed) {
					if (autoSaved) {
						scenarioPassed = RESULT_AUTOSAVED;
					} else {
						scenarioPassed = RESULT_OK;
					}
				}
				larvaTool.debugMessage("Wait " + waitBeforeCleanUp + " ms before clean up");
				try {
					Thread.sleep(waitBeforeCleanUp);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				larvaTool.debugMessage("Close actions");
				boolean remainingMessagesFound = actionFactory.closeLarvaActions(larvaActions);
				if (remainingMessagesFound) {
					if (logLevel.shouldLog(LarvaLogLevel.STEP_PASSED_FAILED))
						output.append(stepFailedMessage("Found one or more messages on actions or in database after scenario executed"));
					scenarioPassed = RESULT_ERROR;
				}
			}

			if (scenarioPassed == RESULT_OK) {
				scenariosPassed.incrementAndGet();
				if (logLevel.shouldLog(LarvaLogLevel.SCENARIO_PASSED_FAILED))
					output.append(scenarioPassedMessage("Scenario '" + shortName + " - " + scenarioDescription + "' passed (" + scenariosFailed.get() + "/" + scenariosPassed.get() + "/" + scenariosTotal + ")"));
				if (config.isSilent() && logLevel.shouldLog(LarvaLogLevel.SCENARIO_PASSED_FAILED)) {
					config.writeSilent("Scenario '" + shortName + " - " + scenarioDescription + "' passed");
				}
			} else if (scenarioPassed == RESULT_AUTOSAVED) {
				scenariosAutosaved.incrementAndGet();
				if (logLevel.shouldLog(LarvaLogLevel.SCENARIO_PASSED_FAILED))
					output.append(scenarioAutosavedMessage("Scenario '" + shortName + " - " + scenarioDescription + "' passed after autosave"));
				if (config.isSilent()) {
					config.writeSilent("Scenario '" + shortName + " - " + scenarioDescription + "' passed after autosave");
				}
			} else {
				scenariosFailed.incrementAndGet();
				if (logLevel.shouldLog(LarvaLogLevel.SCENARIO_FAILED))
					output.append(scenarioFailedMessage("Scenario '" + shortName + " - " + scenarioDescription + "' failed (" + scenariosFailed.get() + "/" + scenariosPassed.get() + "/" + scenariosTotal + ")"));
				if (config.isSilent()) {
					config.writeSilent("Scenario '" + shortName + " - " + scenarioDescription + "' failed");
				}
			}
			output.append("</div>");
			larvaTool.writeHtml(output.toString(), true);
			config.flushWriters();
			return scenarioPassed;
		} catch (Exception e) {
			log.warn("Error occurred while creating Larva Scenario Actions", e);
			larvaTool.errorMessage(e.getClass().getSimpleName() + ": "+e.getMessage(), e);
			return RESULT_ERROR;
		} finally {
			// Cleanup created beans, if they are singletons, they will be closed.
			if (applicationContext != null) {
				try {
					applicationContext.close();
				} catch (Exception e) {
					log.warn("Error occurred while closing Larva ApplicationContext", e);
				}
			}
		}
	}

	private List<String> getSteps(Properties properties) {
		List<String> steps = new ArrayList<>();
		int i = 1;
		boolean lastStepFound = false;
		while (!lastStepFound) {
			boolean stepFound = false;
			Enumeration<?> enumeration = properties.propertyNames();
			while (enumeration.hasMoreElements()) {
				String key = (String) enumeration.nextElement();
				if (key.startsWith("step" + i + ".") && (key.endsWith(".read") || key.endsWith(".write") || (larvaTool.allowReadlineSteps && key.endsWith(".readline")) || key.endsWith(".writeline"))) {
					if (!stepFound) {
						steps.add(key);
						stepFound = true;
						larvaTool.debugMessage("Added step '" + key + "'");
					} else {
						larvaTool.errorMessage("More than one step" + i + " properties found, already found '" + steps.get(steps.size() - 1) + "' before finding '" + key + "'");
					}
				}
			}
			if (!stepFound) {
				lastStepFound = true;
			}
			i++;
		}
		larvaTool.debugMessage(steps.size() + " steps found");
		return steps;
	}

	private String stepPassedMessage(String message) {
		return "<h3 class='passed'>" + XmlEncodingUtils.encodeChars(message) + "</h3>";
	}

	private String stepAutosavedMessage(String message) {
		return "<h3 class='autosaved'>" + XmlEncodingUtils.encodeChars(message) + "</h3>";
	}

	private String stepFailedMessage(String message) {
		return "<h3 class='failed'>" + XmlEncodingUtils.encodeChars(message) + "</h3>";
	}

	private String scenarioPassedMessage(String message) {
		return "<h2 class='passed'>" + XmlEncodingUtils.encodeChars(message) + "</h2>";
	}

	private String scenarioAutosavedMessage(String message) {
		return "<h2 class='autosaved'>" + XmlEncodingUtils.encodeChars(message) + "</h2>";
	}

	private String scenarioFailedMessage(String message) {
		return "<h2 class='failed'>" + XmlEncodingUtils.encodeChars(message) + "</h2>";
	}

}
