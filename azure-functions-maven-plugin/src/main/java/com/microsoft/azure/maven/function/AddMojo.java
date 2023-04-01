/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.maven.function;

import com.microsoft.azure.toolkit.lib.common.exception.AzureExecutionException;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azure.toolkit.lib.legacy.function.configurations.FunctionExtensionVersion;
import com.microsoft.azure.toolkit.lib.legacy.function.template.BindingConfiguration;
import com.microsoft.azure.toolkit.lib.legacy.function.template.BindingTemplate;
import com.microsoft.azure.toolkit.lib.legacy.function.template.FunctionSettingTemplate;
import com.microsoft.azure.toolkit.lib.legacy.function.template.FunctionTemplate;
import com.microsoft.azure.toolkit.lib.legacy.function.template.TemplateResources;
import com.microsoft.azure.toolkit.lib.legacy.function.utils.FunctionUtils;
import lombok.CustomLog;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.IOUtil;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.lang.System.out;
import static javax.lang.model.SourceVersion.isName;

/**
 * Create new Azure Functions (as Java class) and add to current project.
 */
@CustomLog
@Mojo(name = "add")
public class AddMojo extends AbstractFunctionMojo {
    private static final String LOAD_TEMPLATES = "Step 1 of 4: Load all function templates";
    private static final String LOAD_TEMPLATES_DONE = "Successfully loaded all function templates";
    private static final String FIND_TEMPLATE = "Step 2 of 4: Select function template";
    private static final String FIND_TEMPLATE_DONE = "Successfully found function template: ";
    private static final String FIND_TEMPLATE_FAIL = "Function template not found: ";
    private static final String PREPARE_PARAMS = "Step 3 of 4: Prepare required parameters";
    private static final String FOUND_VALID_VALUE = "Found valid value. Skip user input.";
    private static final String SAVE_FILE = "Step 4 of 4: Saving function to file";
    private static final String SAVE_FILE_DONE = "Successfully saved new function at ";
    private static final String FILE_EXIST = "Function already exists at %s. Please specify a different function name.";
    private static final String DEFAULT_INPUT_ERROR_MESSAGE = "Invalid input, please check and try again.";
    private static final String PROMPT_STRING_WITH_DEFAULT_VALUE = "Enter value for %s(Default: %s): ";
    private static final String PROMPT_STRING_WITHOUT_DEFAULT_VALUE = "Enter value for %s: ";
    private static final String FUNCTION_NAME_REGEXP = "^[a-zA-Z][a-zA-Z\\d_\\-]*$";

    //region Properties

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    protected File basedir;

    @Parameter(defaultValue = "${project.compileSourceRoots}", readonly = true, required = true)
    protected List<String> compileSourceRoots;

    /**
     * Package name of the new function.
     *
     * @since 0.1.0
     */
    @Parameter(property = "functions.package")
    protected String functionPackageName;

    /**
     * Name of the new function.
     *
     * @since 0.1.0
     */
    @Parameter(property = "functions.name")
    protected String functionName;

    /**
     * Template for the new function
     *
     * @since 0.1.0
     */
    @Parameter(property = "functions.template")
    protected String functionTemplate;

    //endregion

    //region Getter and Setter

    public String getFunctionPackageName() {
        return functionPackageName;
    }

    public String getFunctionName() {
        return functionName;
    }

    public String getClassName() {
        return getFunctionName().replace('-', '_');
    }

    public String getFunctionTemplate() {
        return functionTemplate;
    }

    protected String getBasedir() {
        return basedir.getAbsolutePath();
    }

    protected String getSourceRoot() {
        return compileSourceRoots == null || compileSourceRoots.isEmpty() ?
                Paths.get(getBasedir(), "src", "main", "java").toString() :
                compileSourceRoots.get(0);
    }

    protected void setFunctionPackageName(String functionPackageName) {
        this.functionPackageName = StringUtils.lowerCase(functionPackageName);
    }

    protected void setFunctionName(String functionName) {
        this.functionName = StringUtils.capitalize(functionName);
    }

    protected void setFunctionTemplate(String functionTemplate) {
        this.functionTemplate = functionTemplate;
    }

    //endregion

    //region Entry Point

    @Override
    @AzureOperation("user/functionapp.add")
    protected void doExecute() throws AzureExecutionException {
        try {
            final FunctionExtensionVersion bundleVersion = getBundleVersion();
            final List<FunctionTemplate> templates = loadAllFunctionTemplates(bundleVersion);

            final FunctionTemplate template = getFunctionTemplate(templates);

            final BindingTemplate bindingTemplate = FunctionUtils.loadBindingTemplate(template.getBindingConfiguration());
            final Map params = prepareRequiredParameters(template, bindingTemplate);

            final String newFunctionClass = substituteParametersInTemplate(template, params);

            saveNewFunctionToFile(newFunctionClass);
        } catch (MojoFailureException | IOException e) {
            throw new AzureExecutionException("Cannot add new java functions.", e);
        }
    }

    //endregion

    //region Load templates
    protected List<FunctionTemplate> loadAllFunctionTemplates(FunctionExtensionVersion bundleVersion) throws AzureExecutionException {
        log.info("");
        log.info(LOAD_TEMPLATES);
        final List<FunctionTemplate> templates = FunctionUtils.loadAllFunctionTemplates()
                .stream()
                .filter(template -> ObjectUtils.anyNull(bundleVersion, template.getSupportedExtensionVersions()) || template.getSupportedExtensionVersions().contains(bundleVersion))
                .collect(Collectors.toList());
        log.info(LOAD_TEMPLATES_DONE);
        return templates;
    }

    //endregion

    //region Get function template
    protected FunctionTemplate getFunctionTemplate(final List<FunctionTemplate> templates) throws IOException, AzureExecutionException, MojoFailureException {
        log.info("");
        log.info(FIND_TEMPLATE);

        if (settings != null && !settings.isInteractiveMode()) {
            assureInputInBatchMode(getFunctionTemplate(),
                str -> getTemplateNames(templates)
                            .stream()
                            .filter(Objects::nonNull)
                            .anyMatch(o -> o.equalsIgnoreCase(str)),
                    this::setFunctionTemplate,
                    true);
        } else {
            assureInputFromUser("template for new function",
                    getFunctionTemplate(),
                    getTemplateNames(templates),
                    this::setFunctionTemplate);
        }
        final FunctionTemplate result = findTemplateByName(templates, getFunctionTemplate());
        getTelemetryProxy().addDefaultProperty(TRIGGER_TYPE, Optional.ofNullable(result.getBindingConfiguration()).map(BindingConfiguration::getType).orElse(null));
        return result;
    }

    protected List<String> getTemplateNames(final List<FunctionTemplate> templates) {
        return templates.stream().map(t -> t.getMetadata().getName()).collect(Collectors.toList());
    }

    protected FunctionTemplate findTemplateByName(final List<FunctionTemplate> templates, final String templateName)
            throws AzureExecutionException {
        log.info("Selected function template: " + templateName);
        final Optional<FunctionTemplate> template = templates.stream()
                .filter(t -> t.getMetadata().getName().equalsIgnoreCase(templateName))
                .findFirst();

        if (template.isPresent()) {
            log.info(FIND_TEMPLATE_DONE + templateName);
            return template.get();
        }

        throw new AzureExecutionException(FIND_TEMPLATE_FAIL + templateName);
    }

    //endregion

    //region Prepare parameters

    protected Map<String, String> prepareRequiredParameters(final FunctionTemplate template,
                                                            final BindingTemplate bindingTemplate)
            throws MojoFailureException {
        log.info("");
        log.info(PREPARE_PARAMS);

        prepareFunctionName();

        preparePackageName();

        final Map<String, String> params = new LinkedHashMap<>();
        params.put("functionName", getFunctionName());
        params.put("className", getClassName());
        params.put("packageName", getFunctionPackageName());

        prepareTemplateParameters(template, bindingTemplate, params);

        displayParameters(params);

        return params;
    }

    protected void prepareFunctionName() throws MojoFailureException {
        log.info("Common parameter [Function Name]: name for both the new function and Java class");

        if (settings != null && !settings.isInteractiveMode()) {
            assureInputInBatchMode(getFunctionName(), str -> StringUtils.isNotEmpty(str) && str.matches(FUNCTION_NAME_REGEXP), this::setFunctionName, true);
        } else {
            assureInputFromUser("Enter value for Function Name: ", getFunctionName(), str -> StringUtils.isNotEmpty(str) && str.matches(FUNCTION_NAME_REGEXP),
                    "Function name must start with a letter and can contain letters, digits, '_' and '-'", this::setFunctionName);
        }
    }

    protected void preparePackageName() throws MojoFailureException {
        log.info("Common parameter [Package Name]: package name of the new Java class");

        if (settings != null && !settings.isInteractiveMode()) {
            assureInputInBatchMode(getFunctionPackageName(), str -> StringUtils.isNotEmpty(str) && isName(str), this::setFunctionPackageName, true);
        } else {
            assureInputFromUser("Enter value for Package Name: ", getFunctionPackageName(), str -> StringUtils.isNotEmpty(str) && isName(str),
                    "Input should be a valid Java package name.", this::setFunctionPackageName);
        }
    }

    protected Map<String, String> prepareTemplateParameters(final FunctionTemplate template,
                                                            final BindingTemplate bindingTemplate,
                                                            final Map<String, String> params)
            throws MojoFailureException {
        final List<String> userPrompt = ObjectUtils.firstNonNull(template.getMetadata().getUserPrompt(), Collections.emptyList());
        for (final String property : userPrompt) {
            String initValue = System.getProperty(property);
            final List<String> options = getOptionsForUserPrompt(property);
            final FunctionSettingTemplate settingTemplate = bindingTemplate == null ?
                    null : bindingTemplate.getSettingTemplateByName(property);
            final String helpMessage = (settingTemplate != null && settingTemplate.getHelp() != null) ?
                    settingTemplate.getHelp() : "";

            log.info(format("Trigger specific parameter [%s]:%s", property,
                    TemplateResources.getResource(helpMessage)));
            if (settings != null && !settings.isInteractiveMode()) {
                if (options != null && options.size() > 0) {
                    final String foundElement = findElementInOptions(options, initValue);
                    initValue = foundElement == null ? options.get(0) : foundElement;
                }
                assureInputInBatchMode(initValue, StringUtils::isNotEmpty, str -> params.put(property, str), false);
            } else {
                if (options == null) {
                    params.put(property, getStringInputFromUser(property, initValue, settingTemplate));
                } else {
                    assureInputFromUser(format("the value for %s: ", property), System.getProperty(property), options, str -> params.put(property, str)
                    );
                }
            }
        }

        return params;
    }

    protected String getStringInputFromUser(String attributeName, String initValue, FunctionSettingTemplate template) {
        final String defaultValue = template == null ? null : template.getDefaultValue();
        final Function<String, Boolean> validator = getStringInputValidator(template);

        if (validator.apply(initValue)) {
            log.info(FOUND_VALID_VALUE);
            return initValue;
        }

        final Scanner scanner = getScanner();
        while (true) {
            out.printf(getStringInputPromptString(attributeName, defaultValue));
            out.flush();
            final String input = scanner.nextLine();
            if (validator.apply(input)) {
                return input;
            } else if (StringUtils.isNotEmpty(defaultValue) && StringUtils.isEmpty(input)) {
                return defaultValue;
            }
            log.warn(getStringInputErrorMessage(template));
        }
    }

    protected String getStringInputErrorMessage(FunctionSettingTemplate template) {
        return (template != null && template.getErrorText() != null) ?
                TemplateResources.getResource(template.getErrorText()) : DEFAULT_INPUT_ERROR_MESSAGE;
    }

    protected String getStringInputPromptString(String attributeName, String defaultValue) {
        return StringUtils.isBlank(defaultValue) ?
                String.format(PROMPT_STRING_WITHOUT_DEFAULT_VALUE, attributeName) :
                String.format(PROMPT_STRING_WITH_DEFAULT_VALUE, attributeName, defaultValue);
    }

    protected Function<String, Boolean> getStringInputValidator(FunctionSettingTemplate template) {
        final String regex = template == null ? null : template.getSettingRegex();
        if (regex == null) {
            return StringUtils::isNotEmpty;
        } else {
            return (attribute) -> StringUtils.isNotEmpty(attribute) && attribute.matches(regex);
        }
    }

    protected void displayParameters(final Map<String, String> params) {
        log.info("");
        log.info("Summary of parameters for function template:");

        params.entrySet().stream().forEach(e -> log.info(format("%s: %s", e.getKey(), e.getValue())));
    }

    //endregion

    //region Substitute parameters

    protected String substituteParametersInTemplate(final FunctionTemplate template, final Map<String, String> params) {
        String ret = template.getFiles().get("function.java");
        for (final Map.Entry<String, String> entry : params.entrySet()) {
            ret = ret.replace(String.format("$%s$", entry.getKey()), entry.getValue());
        }
        return ret;
    }

    //endregion

    //region Save function to file

    protected void saveNewFunctionToFile(final String newFunctionClass) throws IOException, AzureExecutionException {
        log.info("");
        log.info(SAVE_FILE);

        final File packageDir = getPackageDir();

        final File targetFile = getTargetFile(packageDir);

        createPackageDirIfNotExist(packageDir);

        saveToTargetFile(targetFile, newFunctionClass);

        log.info(SAVE_FILE_DONE + targetFile.getAbsolutePath());
    }

    protected File getPackageDir() {
        final String sourceRoot = getSourceRoot();
        final String[] packageName = getFunctionPackageName().split("\\.");
        return Paths.get(sourceRoot, packageName).toFile();
    }

    protected File getTargetFile(final File packageDir) throws AzureExecutionException {
        final String javaFileName = getClassName() + ".java";
        final File targetFile = new File(packageDir, javaFileName);
        if (targetFile.exists()) {
            throw new AzureExecutionException(format(FILE_EXIST, targetFile.getAbsolutePath()));
        }
        return targetFile;
    }

    protected void createPackageDirIfNotExist(final File packageDir) {
        if (!packageDir.exists()) {
            packageDir.mkdirs();
        }
    }

    protected void saveToTargetFile(final File targetFile, final String newFunctionClass) throws IOException {
        try (final OutputStream os = new FileOutputStream(targetFile)) {
            IOUtil.copy(newFunctionClass, os);
        }
    }

    //endregion

    //region Helper methods
    // todo: Support default values for list variables input
    protected void assureInputFromUser(final String prompt, final String initValue, final List<String> options,
                                       final Consumer<String> setter) {
        final String option = findElementInOptions(options, initValue);
        if (option != null) {
            log.info(FOUND_VALID_VALUE);
            setter.accept(option);
            return;
        }

        out.printf("Choose from below options as %s %n", prompt);
        for (int i = 0; i < options.size(); i++) {
            out.printf("%d. %s%n", i, options.get(i));
        }

        assureInputFromUser("Enter index to use: ", null, str -> {
                try {
                    final int index = Integer.parseInt(str);
                    return 0 <= index && index < options.size();
                } catch (Exception e) {
                    return false;
                }
            }, "Invalid index.", str -> {
                final int index = Integer.parseInt(str);
                setter.accept(options.get(index));
            }
        );
    }

    protected void assureInputFromUser(final String prompt, final String initValue,
                                       final Function<String, Boolean> validator, final String errorMessage,
                                       final Consumer<String> setter) {
        if (validator.apply(initValue)) {
            log.info(FOUND_VALID_VALUE);
            setter.accept(initValue);
            return;
        }

        final Scanner scanner = getScanner();

        while (true) {
            out.printf(prompt);
            out.flush();
            try {
                final String input = scanner.nextLine();
                if (validator.apply(input)) {
                    setter.accept(input);
                    break;
                }
            } catch (Exception ignored) {
            }
            // Reaching here means invalid input
            log.warn(errorMessage);
        }
    }

    protected void assureInputInBatchMode(final String input, final Function<String, Boolean> validator,
                                          final Consumer<String> setter, final boolean required)
            throws MojoFailureException {
        if (validator.apply(input)) {
            log.info(FOUND_VALID_VALUE);
            setter.accept(input);
            return;
        }

        if (required) {
            throw new MojoFailureException(String.format("invalid input: %s", input));
        } else {
            out.printf("The input is invalid. Use empty string.%n");
            setter.accept("");
        }
    }

    protected Scanner getScanner() {
        return new Scanner(System.in, "UTF-8");
    }

    @Nullable
    private String findElementInOptions(List<String> options, String item) {
        return options.stream()
                .filter(o -> o != null && o.equalsIgnoreCase(item))
                .findFirst()
                .orElse(null);
    }

    // todo: get options list from templates
    @Nullable
    private List<String> getOptionsForUserPrompt(final String promptName) {
        // HTTP Trigger
        if ("authlevel".equalsIgnoreCase(promptName.trim())) {
            return Arrays.asList("ANONYMOUS", "FUNCTION", "ADMIN");
        }
        // Cosmos DB Trigger
        if (StringUtils.equalsAnyIgnoreCase(promptName.trim(), "createLeaseCollectionIfNotExists", "createLeaseContainerIfNotExists")) {
            return Arrays.asList("true", "false");
        }
        if ("protocol".equalsIgnoreCase(promptName.trim())) {
            return Arrays.asList("NOTSET", "PLAINTEXT", "SSL", "SASLPLAINTEXT", "SASLSSL");
        }
        if ("authenticationMode".equalsIgnoreCase(promptName.trim())) {
            return Arrays.asList("NOTSET", "GSSAPI", "PLAIN", "SCRAMSHA256", "SCRAMSHA512");
        }
        return null;
    }

    //endregion
}
