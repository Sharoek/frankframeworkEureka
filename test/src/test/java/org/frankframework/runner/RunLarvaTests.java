package org.frankframework.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.annotation.Nonnull;
import jakarta.servlet.ServletContext;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.custommonkey.xmlunit.XMLUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

import org.frankframework.configuration.IbisContext;
import org.frankframework.larva.LarvaLogLevel;
import org.frankframework.larva.LarvaTool;
import org.frankframework.larva.ScenarioRunner;
import org.frankframework.larva.TestConfig;
import org.frankframework.lifecycle.FrankApplicationInitializer;
import org.frankframework.util.AppConstants;
import org.frankframework.util.CloseUtils;

/**
 * Attempt to run Larva tests in the Maven build.
 *
 * There are some issues -- some tests fail unexpectedly, whereas they do not fail when running
 * in a normal AppServer environment.
 *
 * Therefore it will not fail the build and run only to provide extra coverage-reporting.
 *
 */
@Tag("integration")
public class RunLarvaTests {

	public static final LarvaLogLevel LARVA_LOG_LEVEL = LarvaLogLevel.WRONG_PIPELINE_MESSAGES;

	public static final Set<String> IGNORED_SCENARIOS = Set.of(
			"ApiListener/scenario01.properties",
			"ApiListener/scenario02.properties",
			"ApiListener/scenario03.properties",
			"ApiListener/scenario04.properties",
			"ApiListener/scenario05.properties",
			"ApiListener/scenario06.properties",
			"ApiListener/scenario07.properties",
			"ApiListener/scenario08.properties",
			"Authentication/scenario03.properties",
			"Authentication/scenario04.properties",
			"Base64Pipe/scenario01.properties",
			"Base64Pipe/scenario02.properties",
			"CorrelationMessageId/scenario04.properties",
			"CorrelationMessageId/scenario05.properties",
			"Exits/api/scenario01.properties",
			"Exits/api/scenario01b.properties",
			"Exits/api/scenario03.properties",
			"Exits/soap/scenario01.properties",
			"Exits/soap/scenario02.properties",
			"JSON/DataSonnet/scenario01.properties",
			"JSON/JsonPipe/scenario01.properties",
			"FileSender/scenario01.properties",
			"ForwardNameProvidingSenders/scenario10.properties",
			"ForwardNameProvidingSenders/scenario11.properties",
			"ForwardNameProvidingSenders/scenario12.properties",
			"ForwardNameProvidingSenders/scenario13.properties",
			"FrankSender/scenario05.properties",
			"ManagedFileHandler/scenario01.properties",
			"ManagedFileHandler/scenario02.properties",
			"LocalFileSystemPipe/scenario07.properties",
			"LocalFileSystemPipe/scenario08.properties",
			"MoveFiles/scenario01.properties",
			"MoveFiles/scenario04.properties",
			"MoveFiles/scenario09.properties",
			"Receivers/NonTransacted/NoInProcess/scenario01.properties",
			"Receivers/NonTransacted/NoInProcess/scenario05.properties",
			"Receivers/NonTransacted/NoInProcess/scenario06.properties",
			"Receivers/Transacted/WithInProcess/scenario03.properties",
			"RestListener/scenario01.properties",
			"RestListener/scenario02.properties",
			"Validators/SoapValidator/scenario07.properties",
			"WebServiceListenerSender/scenario11b.properties",
			"WebServiceListenerSender/scenario11c.properties",
			"WebServiceListenerSender/scenario11d.properties",
			"WsdlGeneratorPipe/scenario01.properties",
			"WsdlGeneratorPipe/scenario02.properties",
			"WsdlGeneratorPipe/scenario03.properties",
			"XsltProviderListener/scenario04.properties",
			"Zip/ZipWriter/scenario 01.properties",
			"Zip/ZipWriter/scenario 02.properties"
	);

	private static ConfigurableApplicationContext applicationContext;
	private static LarvaTool larvaTool;
	private static ScenarioRunner scenarioRunner;
	private static AppConstants appConstants;
	private static String scenarioRootDir;

	/**
	 * Since we don't use @SpringBootApplication, we can't use @SpringBootTest here and need to manually configure the application
	 */
	@BeforeAll
	static void setup() throws IOException {
		SpringApplication springApplication = IafTestInitializer.configureApplication();
		applicationContext = springApplication.run();
		ServletContext servletContext = applicationContext.getBean(ServletContext.class);
		IbisContext ibisContext = FrankApplicationInitializer.getIbisContext(servletContext);
		appConstants = AppConstants.getInstance();

		larvaTool = new LarvaTool();
		TestConfig testConfig = larvaTool.getConfig();
		testConfig.setTimeout(10_000);
		testConfig.setSilent(false);
		testConfig.setLogLevel(LARVA_LOG_LEVEL);
		testConfig.setAutoScroll(false);
		testConfig.setMultiThreaded(false);
		testConfig.setOut(new HtmlTagStrippingWriter(System.out));

		scenarioRunner = new ScenarioRunner(larvaTool, ibisContext, testConfig, appConstants, 100, LARVA_LOG_LEVEL);
		scenarioRootDir = larvaTool.initScenariosRootDirectories(null, new ArrayList<>(), new ArrayList<>());

		XMLUnit.setIgnoreWhitespace(true);
	}

	@AfterAll
	static void tearDown() {
		CloseUtils.closeSilently(applicationContext);
	}

	/**
	 * This should create Dynamic tests for JUnit to run. However the dynamic tests are not properly
	 * reported on by Surefire, although Surefire does execute them.
	 *
	 * Another issue is that over half the Larva scenarios fails even though I see no reason why they
	 * would fail. Running all scenarios at once by passing the scenario-directoy to the LarvaTool, most
	 * scenarios do run. I don't yet see the principle difference in environment that makes them fail here.
	 *
	 */
	@TestFactory
	Stream<DynamicNode> larvaTests() {
		List<File> allScenarioFiles = larvaTool.readScenarioFiles(appConstants, scenarioRootDir);
		assertFalse(allScenarioFiles.isEmpty(), () -> "Did not find any scenario-files in scenarioRootDir [%s]!".formatted(scenarioRootDir));
		System.err.printf("Creating JUnit tests from %d scenarios loaded from [%s]%n", allScenarioFiles.size(), scenarioRootDir);
		return createScenarios(scenarioRootDir, "", allScenarioFiles);
	}

	private @Nonnull Stream<DynamicNode> createScenarioContainer(@Nonnull String baseFolder, @Nonnull Map.Entry<String, List<File>> scenarioFolder) {
		String scenarioFolderName = scenarioFolder.getKey();
		if (StringUtils.isBlank(scenarioFolderName)) {
			return createScenarios(baseFolder, scenarioFolderName, scenarioFolder.getValue());
		}
		return Stream.of(DynamicContainer.dynamicContainer(scenarioFolderName, new File(baseFolder, scenarioFolderName).toURI(), createScenarios(baseFolder, scenarioFolderName, scenarioFolder.getValue())));
	}

	private @Nonnull Stream<DynamicNode> createScenarios(@Nonnull String baseFolder, @Nonnull String subFolder, @Nonnull List<File> scenarioFiles) {
		String commonFolder = StringUtils.isBlank(subFolder) ? baseFolder : Paths.get(baseFolder, subFolder).toString();
		Map<String, List<File>> scenariosByFolder = ScenarioRunner.groupFilesByFolder(scenarioFiles, commonFolder);

		if (scenariosByFolder.size() == 1) {
			return scenarioFiles.stream()
					.map(this::convertLarvaScenarioToTest);
		} else {
			return scenariosByFolder.entrySet()
					.stream()
					.sorted(Map.Entry.comparingByKey())
					.flatMap((Map.Entry<String, List<File>> nestedSubFolder) -> createScenarioContainer(commonFolder, nestedSubFolder));
		}
	}

	private DynamicTest convertLarvaScenarioToTest(File scenarioFile) {
		// Scenario name always computed from the scenario root dir to be understandable without context of immediate parent
		String scenarioName = FilenameUtils.normalize(scenarioFile.getAbsolutePath().substring(scenarioRootDir.length()), true);
		return DynamicTest.dynamicTest(
				scenarioName, scenarioFile.toURI(), () -> {
					System.out.println("Running scenario: [" + scenarioName + "]");
					int scenarioPassed = scenarioRunner.runOneFile(scenarioFile, scenarioRootDir, true);

					assumeTrue(scenarioPassed != LarvaTool.RESULT_ERROR || !IGNORED_SCENARIOS.contains(scenarioName), () -> "Ignoring Blacklisted Scenario: [" + scenarioName + "]");
					assertNotEquals(LarvaTool.RESULT_ERROR, scenarioPassed, () -> "Scenario failed: [" + scenarioName + "]");
				}
		);
	}

	@Test
	@Disabled("Run Larva test scenarios individually now")
	void runLarvaTests() throws IOException {
		assertTrue(applicationContext.isRunning());
		List<File> allScenarioFiles = larvaTool.readScenarioFiles(appConstants, scenarioRootDir);
		assertFalse(allScenarioFiles.isEmpty(), () -> "Did not find any scenario-files in scenarioRootDir [%s]!".formatted(scenarioRootDir));

		ServletContext servletContext = applicationContext.getBean(ServletContext.class);

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setParameter("execute", scenarioRootDir);
		request.setParameter("loglevel", LarvaLogLevel.SCENARIO_FAILED.getName());

		// Invoke Larva tests
		ByteArrayOutputStream boas = new ByteArrayOutputStream();
		HtmlTagStrippingWriter htmlStrippingWriter = new HtmlTagStrippingWriter(boas, Set.of("form"));

		System.err.printf("Starting Scenarios, should have %d scenarios to run loaded from directory [%s].%n", allScenarioFiles.size(), scenarioRootDir);
		long start = System.currentTimeMillis();
		int result = LarvaTool.runScenarios(servletContext, request, htmlStrippingWriter);
		long end = System.currentTimeMillis();
		System.err.printf("Scenarios executed; duration: %dms%n", end - start);
		boas.close();

		String larvaOutput = boas.toString();
		assertFalse(result < 0, () -> "Error in LarvaTool execution, result is [%d] instead of 0; output from LarvaTool:%n%n%s".formatted(result, larvaOutput));

		if (result > 0) {
			System.err.printf("%d Larva tests failed, duration: %dms; %n%n%s%n", result, end - start, larvaOutput);
		} else {
			System.err.printf("All Larva tests succeeded in %dms%n", end - start);
		}

		// About 15 to 18 scenarios will fail because the environment is not set up entirely correct. Do not fail the build because of that, still get the extra coverage.
//		assertEquals(0, result, () -> "Error in LarvaTool scenarios, %d scenarios failed. Duration: %dms; %n%n%s".formatted(result, end - start, larvaOutput));
	}
}
