/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.common.utils;


import lombok.CustomLog;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * copied from: https://github.com/microsoft/azure-tools-for-java/blob/
 * 0efe5e430305369e17dadc214c256cbb4a906372/Utils/azuretools-core/src/com/microsoft/azuretools/utils/CommandUtils.java
 */
@CustomLog
public class CommandUtils {
    private static final String WINDOWS_STARTER = "cmd.exe";
    private static final String LINUX_MAC_STARTER = "/bin/sh";
    private static final String WINDOWS_SWITCHER = "/c";
    private static final String LINUX_MAC_SWITCHER = "-c";
    private static final String DEFAULT_WINDOWS_SYSTEM_ROOT = System.getenv("SystemRoot");
    private static final String DEFAULT_MAC_LINUX_PATH = "/bin/";

    public static List<String> resolveCommandPath(String command) {
        final List<String> list = new ArrayList<>();
        try {

            final String output = CommandUtils.exec((isWindows() ? "where " : "which ") + command);
            if (StringUtils.isBlank(output)) {
                return Collections.emptyList();
            }

            for (final String outputLine : output.split("[\\r\\n]")) {
                final File file = new File(StringUtils.trim(outputLine));
                if (!file.exists() || !file.isFile()) {
                    continue;
                }

                list.add(file.getAbsolutePath());
            }
        } catch (IOException ignored) {
            // ignore
        }
        return list;
    }

    public static String exec(final String commandWithArgs) throws IOException {
        return exec(commandWithArgs, new HashMap<>());
    }

    public static String exec(final String commandWithArgs, String cwd) throws IOException {
        return exec(commandWithArgs, new HashMap<>(), cwd);
    }

    public static String exec(final String commandWithArgs, Map<String, String> env) throws IOException {
        return exec(commandWithArgs, env, null);
    }

    public static String exec(final String commandWithArgs, Map<String, String> env, String cwd) throws IOException {
        return exec(commandWithArgs, env, cwd, false);
    }

    public static String exec(final String commandWithArgs, String cwd, boolean mergeErrorStream) throws IOException {
        return exec(commandWithArgs, new HashMap<>(), cwd, mergeErrorStream);
    }

    public static String exec(final String commandWithArgs, Map<String, String> env, String cwd, boolean mergeErrorStream) throws IOException {
        final String starter = isWindows() ? WINDOWS_STARTER : LINUX_MAC_STARTER;
        final String switcher = isWindows() ? WINDOWS_SWITCHER : LINUX_MAC_SWITCHER;
        final String workingDirectory = StringUtils.firstNonBlank(cwd, getSafeWorkingDirectory());
        if (StringUtils.isEmpty(workingDirectory)) {
            final IllegalStateException exception = new IllegalStateException("A Safe Working directory could not be found to execute command from.");
            log.error(exception);
            throw exception;
        }
        final String commandWithPath = isWindows() || isWSL() ? commandWithArgs : String.format("export PATH=$PATH:/usr/local/bin ; %s", commandWithArgs);
        return executeCommandAndGetOutput(starter, switcher, commandWithPath, new File(workingDirectory), env, mergeErrorStream);
    }

    private static String executeCommandAndGetOutput(final String starter, final String switcher, final String commandWithArgs,
                                                     final File directory, Map<String, String> env, boolean mergeErrorStream) throws IOException {
        final CommandLine commandLine = new CommandLine(starter);
        commandLine.addArgument(switcher, false);
        commandLine.addArgument(commandWithArgs, false);
        return executeCommandAndGetOutput(commandLine, directory, env, mergeErrorStream);
    }

    private static String executeCommandAndGetOutput(final CommandLine commandLine, final File directory, Map<String, String> env, boolean mergeErrorStream)
        throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = mergeErrorStream ? out : new ByteArrayOutputStream();
        final PumpStreamHandler streamHandler = new PumpStreamHandler(out, err);
        final DefaultExecutor executor = new DefaultExecutor();
        executor.setWorkingDirectory(directory);
        executor.setStreamHandler(streamHandler);
        executor.setExitValues(new int[]{0});
        try {
            Map<String, String> newEnv = new HashMap<>(System.getenv());
            if (env != null) {
                newEnv.putAll(env);
            }
            executor.execute(commandLine, newEnv);
            final String result = StringUtils.trimToEmpty(out.toString());
            if (!mergeErrorStream && err.size() > 0 && StringUtils.isEmpty(result)) {
                throw new IOException(StringUtils.trim(err.toString()));
            }
            return result;
        } finally {
            out.close();
            err.close();
        }
    }

    public static boolean isWindows() {
        return SystemUtils.IS_OS_WINDOWS;
    }

    public static boolean isWSL() {
        return SystemUtils.IS_OS_LINUX && System.getenv("WSL_DISTRO_NAME") != null;
    }

    private static String getSafeWorkingDirectory() {
        if (isWindows()) {
            if (StringUtils.isEmpty(DEFAULT_WINDOWS_SYSTEM_ROOT)) {
                return null;
            }
            return DEFAULT_WINDOWS_SYSTEM_ROOT + "\\system32";
        } else {
            return DEFAULT_MAC_LINUX_PATH;
        }
    }
}
