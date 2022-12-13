/*
 * Copyright (C) 2021-2022, Simeon Andreev <simeon.danailov.andreev@gmail.com> and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.pgm;

import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_DIFFTOOL_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_DIFF_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_CMD;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_PROMPT;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_TOOL;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.jgit.internal.diffmergetool.DiffTools;
import org.eclipse.jgit.internal.diffmergetool.ExternalDiffTool;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.Before;
import org.junit.Test;

/**
 * Testing the {@code difftool} command.
 */
public class DiffToolTest extends ToolTestCase {

	private static final String DIFF_TOOL = CONFIG_DIFFTOOL_SECTION;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		configureEchoTool(TOOL_NAME);
	}

	@Test(expected = Die.class)
	public void testUndefinedTool() throws Exception {
		String toolName = "undefined";
		String[] conflictingFilenames = createUnstagedChanges();

		List<String> expectedErrors = new ArrayList<>();
		for (String changedFilename : conflictingFilenames) {
			expectedErrors.add("External diff tool is not defined: " + toolName);
			expectedErrors.add("compare of " + changedFilename + " failed");
		}

		runAndCaptureUsingInitRaw(expectedErrors, DIFF_TOOL, "--no-prompt",
				"--tool", toolName);
		fail("Expected exception to be thrown due to undefined external tool");
	}

	@Test(expected = Die.class)
	public void testUserToolWithCommandNotFoundError() throws Exception {
		String toolName = "customTool";

		int errorReturnCode = 127; // command not found
		String command = "exit " + errorReturnCode;

		StoredConfig config = db.getConfig();
		config.setString(CONFIG_DIFFTOOL_SECTION, toolName, CONFIG_KEY_CMD,
				command);

		createMergeConflict();
		runAndCaptureUsingInitRaw(DIFF_TOOL, "--no-prompt", "--tool", toolName);

		fail("Expected exception to be thrown due to external tool exiting with error code: "
				+ errorReturnCode);
	}

	@Test(expected = Die.class)
	public void testEmptyToolName() throws Exception {
		assumeLinuxPlatform();

		String emptyToolName = "";

		StoredConfig config = db.getConfig();
		// the default diff tool is configured without a subsection
		String subsection = null;
		config.setString(CONFIG_DIFF_SECTION, subsection, CONFIG_KEY_TOOL,
				emptyToolName);

		createUnstagedChanges();

		String araxisErrorLine = "compare: unrecognized option `-wait' @ error/compare.c/CompareImageCommand/1123.";
		String[] expectedErrorOutput = { araxisErrorLine, araxisErrorLine, };
		runAndCaptureUsingInitRaw(Arrays.asList(expectedErrorOutput), DIFF_TOOL,
				"--no-prompt");
		fail("Expected exception to be thrown due to external tool exiting with an error");
	}

	@Test
	public void testToolWithPrompt() throws Exception {
		String[] inputLines = {
				"y", // accept launching diff tool
				"y", // accept launching diff tool
		};

		String[] conflictingFilenames = createUnstagedChanges();
		String[] expectedOutput = getExpectedCompareOutput(conflictingFilenames);

		String option = "--tool";

		InputStream inputStream = createInputStream(inputLines);
		assertArrayOfLinesEquals("Incorrect output for option: " + option,
				expectedOutput, runAndCaptureUsingInitRaw(inputStream,
						DIFF_TOOL, "--prompt", option, TOOL_NAME));
	}

	@Test
	public void testToolAbortLaunch() throws Exception {
		String[] inputLines = {
				"y", // accept launching diff tool
				"n", // don't launch diff tool
		};

		String[] conflictingFilenames = createUnstagedChanges();
		int abortIndex = 1;
		String[] expectedOutput = getExpectedAbortOutput(conflictingFilenames, abortIndex);

		String option = "--tool";

		InputStream inputStream = createInputStream(inputLines);
		assertArrayOfLinesEquals("Incorrect output for option: " + option,
				expectedOutput,
				runAndCaptureUsingInitRaw(inputStream, DIFF_TOOL, "--prompt", option,
						TOOL_NAME));
	}

	@Test(expected = Die.class)
	public void testNotDefinedTool() throws Exception {
		createUnstagedChanges();

		runAndCaptureUsingInitRaw(DIFF_TOOL, "--tool", "undefined");
		fail("Expected exception when trying to run undefined tool");
	}

	@Test
	public void testTool() throws Exception {
		String[] conflictFilenames = createUnstagedChanges();
		String[] expectedOutput = getExpectedToolOutputNoPrompt(conflictFilenames);

		String[] options = {
				"--tool",
				"-t",
		};

		for (String option : options) {
			assertArrayOfLinesEquals("Incorrect output for option: " + option,
					expectedOutput,
					runAndCaptureUsingInitRaw(DIFF_TOOL, option,
							TOOL_NAME));
		}
	}

	@Test
	public void testToolTrustExitCode() throws Exception {
		String[] conflictingFilenames = createUnstagedChanges();
		String[] expectedOutput = getExpectedToolOutputNoPrompt(conflictingFilenames);

		String[] options = { "--tool", "-t", };

		for (String option : options) {
			assertArrayOfLinesEquals("Incorrect output for option: " + option,
					expectedOutput, runAndCaptureUsingInitRaw(DIFF_TOOL,
							"--trust-exit-code", option, TOOL_NAME));
		}
	}

	@Test
	public void testToolNoGuiNoPromptNoTrustExitcode() throws Exception {
		String[] conflictingFilenames = createUnstagedChanges();
		String[] expectedOutput = getExpectedToolOutputNoPrompt(conflictingFilenames);

		String[] options = { "--tool", "-t", };

		for (String option : options) {
			assertArrayOfLinesEquals("Incorrect output for option: " + option,
					expectedOutput, runAndCaptureUsingInitRaw(DIFF_TOOL,
							"--no-gui", "--no-prompt", "--no-trust-exit-code",
							option, TOOL_NAME));
		}
	}

	@Test
	public void testToolCached() throws Exception {
		String[] conflictingFilenames = createStagedChanges();
		Pattern[] expectedOutput = getExpectedCachedToolOutputNoPrompt(conflictingFilenames);

		String[] options = { "--cached", "--staged", };

		for (String option : options) {
			assertArrayOfMatchingLines("Incorrect output for option: " + option,
					expectedOutput, runAndCaptureUsingInitRaw(DIFF_TOOL,
							option, "--tool", TOOL_NAME));
		}
	}

	@Test
	public void testToolHelp() throws Exception {
		List<String> expectedOutput = new ArrayList<>();

		DiffTools diffTools = new DiffTools(db);
		Map<String, ExternalDiffTool> predefinedTools = diffTools
				.getPredefinedTools(true);
		List<ExternalDiffTool> availableTools = new ArrayList<>();
		List<ExternalDiffTool> notAvailableTools = new ArrayList<>();
		for (ExternalDiffTool tool : predefinedTools.values()) {
			if (tool.isAvailable()) {
				availableTools.add(tool);
			} else {
				notAvailableTools.add(tool);
			}
		}

		expectedOutput.add(
				"'git difftool --tool=<tool>' may be set to one of the following:");
		for (ExternalDiffTool tool : availableTools) {
			String toolName = tool.getName();
			expectedOutput.add(toolName);
		}
		String customToolHelpLine = TOOL_NAME + "." + CONFIG_KEY_CMD + " "
				+ getEchoCommand();
		expectedOutput.add("user-defined:");
		expectedOutput.add(customToolHelpLine);
		expectedOutput.add(
				"The following tools are valid, but not currently available:");
		for (ExternalDiffTool tool : notAvailableTools) {
			String toolName = tool.getName();
			expectedOutput.add(toolName);
		}
		String[] userDefinedToolsHelp = {
				"Some of the tools listed above only work in a windowed",
				"environment. If run in a terminal-only session, they will fail.",
		};
		expectedOutput.addAll(Arrays.asList(userDefinedToolsHelp));

		String option = "--tool-help";
		assertArrayOfLinesEquals("Incorrect output for option: " + option,
				expectedOutput.toArray(new String[0]),
				runAndCaptureUsingInitRaw(DIFF_TOOL, option));
	}

	private void configureEchoTool(String toolName) {
		StoredConfig config = db.getConfig();
		// the default diff tool is configured without a subsection
		String subsection = null;
		config.setString(CONFIG_DIFF_SECTION, subsection, CONFIG_KEY_TOOL,
				toolName);

		String command = getEchoCommand();

		config.setString(CONFIG_DIFFTOOL_SECTION, toolName, CONFIG_KEY_CMD,
				command);
		/*
		 * prevent prompts as we are running in tests and there is no user to
		 * interact with on the command line
		 */
		config.setString(CONFIG_DIFFTOOL_SECTION, toolName, CONFIG_KEY_PROMPT,
				String.valueOf(false));
	}

	private String[] getExpectedToolOutputNoPrompt(String[] conflictingFilenames) {
		String[] expectedToolOutput = new String[conflictingFilenames.length];
		for (int i = 0; i < conflictingFilenames.length; ++i) {
			String newPath = conflictingFilenames[i];
			Path fullPath = getFullPath(newPath);
			expectedToolOutput[i] = fullPath.toString();
		}
		return expectedToolOutput;
	}

	private Pattern[] getExpectedCachedToolOutputNoPrompt(String[] conflictingFilenames) {
		String tmpDir = System.getProperty("java.io.tmpdir");
		if (tmpDir.endsWith(File.separator)) {
			tmpDir = tmpDir.substring(0, tmpDir.length() - 1);
		}
		Pattern emptyPattern = Pattern.compile("");
		List<Pattern> expectedToolOutput = new ArrayList<>();
		for (int i = 0; i < conflictingFilenames.length; ++i) {
			String changedFilename = conflictingFilenames[i];
			Path fullPath = getFullPath(changedFilename);
			String filename = fullPath.getFileName().toString();
			String regexp = tmpDir + File.separatorChar + filename
					+ "_REMOTE_.*";
			Pattern pattern = Pattern.compile(regexp);
			expectedToolOutput.add(pattern);
			expectedToolOutput.add(emptyPattern);
		}
		expectedToolOutput.add(emptyPattern);
		return expectedToolOutput.toArray(new Pattern[0]);
	}

	private String[] getExpectedCompareOutput(String[] conflictingFilenames) {
		List<String> expected = new ArrayList<>();
		int n = conflictingFilenames.length;
		for (int i = 0; i < n; ++i) {
			String changedFilename = conflictingFilenames[i];
			expected.add(
					"Viewing (" + (i + 1) + "/" + n + "): '" + changedFilename
							+ "'");
			expected.add("Launch '" + TOOL_NAME + "' [Y/n]?");
			Path fullPath = getFullPath(changedFilename);
			expected.add(fullPath.toString());
		}
		return expected.toArray(new String[0]);
	}

	private String[] getExpectedAbortOutput(String[] conflictingFilenames,
			int abortIndex) {
		List<String> expected = new ArrayList<>();
		int n = conflictingFilenames.length;
		for (int i = 0; i < n; ++i) {
			String changedFilename = conflictingFilenames[i];
			expected.add(
					"Viewing (" + (i + 1) + "/" + n + "): '" + changedFilename
							+ "'");
			expected.add("Launch '" + TOOL_NAME + "' [Y/n]?");
			if (i == abortIndex) {
				break;
			}
			Path fullPath = getFullPath(changedFilename);
			expected.add(fullPath.toString());
		}
		return expected.toArray(new String[0]);
	}

	private static String getEchoCommand() {
		/*
		 * use 'REMOTE' placeholder, as it will be replaced by a file path
		 * within the repository.
		 */
		return "(echo \"$REMOTE\")";
	}
}
