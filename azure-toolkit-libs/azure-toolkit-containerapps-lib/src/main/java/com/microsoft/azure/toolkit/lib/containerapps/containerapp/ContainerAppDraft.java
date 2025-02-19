/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.containerapps.containerapp;

import com.azure.resourcemanager.appcontainers.implementation.ContainerAppImpl;
import com.azure.resourcemanager.appcontainers.models.ActiveRevisionsMode;
import com.azure.resourcemanager.appcontainers.models.BuildResource;
import com.azure.resourcemanager.appcontainers.models.Configuration;
import com.azure.resourcemanager.appcontainers.models.Container;
import com.azure.resourcemanager.appcontainers.models.ContainerApps;
import com.azure.resourcemanager.appcontainers.models.ContainerResources;
import com.azure.resourcemanager.appcontainers.models.EnvironmentVar;
import com.azure.resourcemanager.appcontainers.models.RegistryCredentials;
import com.azure.resourcemanager.appcontainers.models.Scale;
import com.azure.resourcemanager.appcontainers.models.Secret;
import com.azure.resourcemanager.appcontainers.models.Template;
import com.azure.resourcemanager.containerregistry.models.RegistryTaskRun;
import com.google.common.collect.Sets;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.common.action.Action;
import com.microsoft.azure.toolkit.lib.common.action.AzureActionManager;
import com.microsoft.azure.toolkit.lib.common.bundle.AzureString;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.messager.AzureMessager;
import com.microsoft.azure.toolkit.lib.common.messager.IAzureMessager;
import com.microsoft.azure.toolkit.lib.common.model.AzResource;
import com.microsoft.azure.toolkit.lib.common.model.Region;
import com.microsoft.azure.toolkit.lib.common.model.Subscription;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azure.toolkit.lib.common.operation.OperationContext;
import com.microsoft.azure.toolkit.lib.common.task.AzureTaskManager;
import com.microsoft.azure.toolkit.lib.common.utils.Utils;
import com.microsoft.azure.toolkit.lib.containerapps.environment.ContainerAppsEnvironment;
import com.microsoft.azure.toolkit.lib.containerapps.environment.ContainerAppsEnvironmentDraft;
import com.microsoft.azure.toolkit.lib.containerapps.model.EnvironmentType;
import com.microsoft.azure.toolkit.lib.containerapps.model.IngressConfig;
import com.microsoft.azure.toolkit.lib.containerapps.model.ResourceConfiguration;
import com.microsoft.azure.toolkit.lib.containerapps.model.RevisionMode;
import com.microsoft.azure.toolkit.lib.containerapps.model.WorkloadProfile;
import com.microsoft.azure.toolkit.lib.containerregistry.AzureContainerRegistry;
import com.microsoft.azure.toolkit.lib.containerregistry.AzureContainerRegistryModule;
import com.microsoft.azure.toolkit.lib.containerregistry.ContainerRegistry;
import com.microsoft.azure.toolkit.lib.containerregistry.ContainerRegistryDraft;
import com.microsoft.azure.toolkit.lib.containerregistry.model.Sku;
import com.microsoft.azure.toolkit.lib.resource.ResourceGroup;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.microsoft.azure.toolkit.lib.containerregistry.ContainerRegistry.ACR_IMAGE_SUFFIX;

public class ContainerAppDraft extends ContainerApp implements AzResource.Draft<ContainerApp, com.azure.resourcemanager.appcontainers.models.ContainerApp> {

    @Getter
    @Nullable
    private final ContainerApp origin;

    @Getter
    @Setter
    private Config config;

    protected ContainerAppDraft(@Nonnull String name, @Nonnull String resourceGroupName, @Nonnull ContainerAppModule module) {
        super(name, resourceGroupName, module);
        this.origin = null;
    }

    protected ContainerAppDraft(@Nonnull ContainerApp origin) {
        super(origin);
        this.origin = origin;
    }

    @Override
    public void reset() {
        this.config = null;
    }

    @Nonnull
    @Override
    @AzureOperation(name = "azure/containerapps.create_app.app", params = {"this.getName()"})
    public com.azure.resourcemanager.appcontainers.models.ContainerApp createResourceInAzure() {
        final ContainerApps client = Objects.requireNonNull(((ContainerAppModule) getModule()).getClient());

        final ContainerAppsEnvironment containerAppsEnvironment = Objects.requireNonNull(ensureConfig().getEnvironment(),
            "Environment is required to create Container app.");
        if (containerAppsEnvironment.isDraftForCreating()) {
            ((ContainerAppsEnvironmentDraft) containerAppsEnvironment).commit();
        }
        final ImageConfig imageConfig = Objects.requireNonNull(this.getImageConfig(), "Image is required to create Container app.");
        buildImageIfNeeded(imageConfig);
        final Configuration configuration = new Configuration();
        Optional.ofNullable(ensureConfig().getRevisionMode()).ifPresent(mode ->
            configuration.withActiveRevisionsMode(ActiveRevisionsMode.fromString(ensureConfig().getRevisionMode().getValue())));
        configuration.withSecrets(Optional.ofNullable(getSecret(imageConfig)).map(Collections::singletonList).orElse(Collections.emptyList()));
        configuration.withRegistries(Optional.ofNullable(getRegistryCredential(imageConfig)).map(Collections::singletonList).orElse(Collections.emptyList()));
        configuration.withIngress(Optional.ofNullable(ensureConfig().getIngressConfig()).map(IngressConfig::toIngress).orElse(null));
        final ResourceConfiguration resourceConfiguration = ensureConfig().getResourceConfiguration();
        final Template template = new Template()
            .withContainers(ImageConfig.toContainers(imageConfig, resourceConfiguration))
            .withScale(ScaleConfig.toScale(this.getScaleConfig()));
        AzureMessager.getMessager().progress(AzureString.format("Creating Azure Container App({0})...", this.getName()));
        final String workloadProfile = containerAppsEnvironment.getEnvironmentType() == EnvironmentType.ConsumptionOnly ? null :
            Optional.ofNullable(getResourceConfiguration()).map(ResourceConfiguration::getWorkloadProfile).map(WorkloadProfile::getName).orElse(WorkloadProfile.CONSUMPTION);
        final com.azure.resourcemanager.appcontainers.models.ContainerApp result = client.define(ensureConfig().getName())
            .withRegion(com.azure.core.management.Region.fromName(containerAppsEnvironment.getRegion().getName()))
            .withExistingResourceGroup(Objects.requireNonNull(ensureConfig().getResourceGroup(), "Resource Group is required to create Container app.").getResourceGroupName())
            .withManagedEnvironmentId(containerAppsEnvironment.getId())
            .withConfiguration(configuration)
            .withTemplate(template)
            .withWorkloadProfileName(workloadProfile)
            .create();
        final Action<ContainerApp> updateImage = AzureActionManager.getInstance().getAction(ContainerApp.UPDATE_IMAGE).bind(this);
        final Action<ContainerApp> browse = AzureActionManager.getInstance().getAction(ContainerApp.BROWSE).bind(this);
        AzureMessager.getMessager().success(AzureString.format("Azure Container App({0}) is successfully created.", this.getName()), browse, updateImage);

        final Action<String> learnMore = AzureActionManager.getInstance().getAction(Action.OPEN_URL).withLabel("Learn More").bind("https://aka.ms/azuretools-aca-stack");
        final Action<String> openPortal = AzureActionManager.getInstance().getAction(Action.OPEN_URL).withLabel("Open Portal").bind(this.getPortalUrl());
        AzureMessager.getMessager().info("Azure container apps offers an automatic memory fitting experience for Java developers. To take advantage of this Java-optimized feature, please set your development stack to `Java` in the portal.", learnMore, openPortal);
        return result;
    }

    // todo: support update workload profile properties
    @Nonnull
    @Override
    @AzureOperation(name = "azure/containerapps.update_app.app", params = {"this.getName()"})
    public com.azure.resourcemanager.appcontainers.models.ContainerApp updateResourceInAzure(@Nonnull com.azure.resourcemanager.appcontainers.models.ContainerApp origin) {
        final IAzureMessager messager = AzureMessager.getMessager();
        final Config config = ensureConfig();
        final ImageConfig imageConfig = config.getImageConfig();
        final IngressConfig ingressConfig = config.getIngressConfig();
        final RevisionMode revisionMode = config.getRevisionMode();
        final ScaleConfig scaleConfig = config.getScaleConfig();

        final boolean isImageModified = Objects.nonNull(imageConfig) && !Objects.equals(imageConfig, super.getImageConfig());
        final boolean isIngressConfigModified = Objects.nonNull(ingressConfig) && !Objects.equals(ingressConfig, super.getIngressConfig());
        final boolean isRevisionModeModified = !Objects.equals(revisionMode, super.getRevisionMode());
        final boolean isScaleModified = !Objects.equals(scaleConfig, super.getScaleConfig());
        final boolean isModified = isImageModified || isIngressConfigModified || isRevisionModeModified || isScaleModified;
        if (!isModified) {
            return origin;
        }
        buildImageIfNeeded(imageConfig);
        final ContainerAppImpl update = (ContainerAppImpl) (isImageModified ? this.updateImage(origin) : origin.update());
        final Configuration configuration = update.configuration();
        if (!isImageModified) {
            // anytime you want to update the container app, you need to include the secrets but that is not retrieved by default
            final List<Secret> secrets = origin.listSecrets().value().stream().map(s -> new Secret().withName(s.name()).withValue(s.value())).collect(Collectors.toList());
            final List<RegistryCredentials> registries = Optional.ofNullable(origin.configuration().registries()).map(ArrayList::new).orElseGet(ArrayList::new);
            configuration.withRegistries(registries).withSecrets(secrets);
        }
        // ["properties"]["template"]["containers"]
        if (isIngressConfigModified) {
            configuration.withIngress(ingressConfig.toIngress());
        }
        if (isRevisionModeModified) {
            configuration.withActiveRevisionsMode(revisionMode.toActiveRevisionMode());
        }
        if (isScaleModified) {
            if (isImageModified) {
                update.withTemplate(update.template().withScale(ScaleConfig.toScale(scaleConfig)));
            } else {
                update.withTemplate(new Template().withScale(ScaleConfig.toScale(scaleConfig)));
            }
        }
        update.withConfiguration(configuration);
        messager.progress(AzureString.format("Updating Container App({0})...", getName()));
        final com.azure.resourcemanager.appcontainers.models.ContainerApp result = update.apply();
        final Action<ContainerApp> browse = AzureActionManager.getInstance().getAction(ContainerApp.BROWSE).bind(this);
        messager.success(AzureString.format("Container App({0}) is successfully updated.", getName()), browse);
        if (isImageModified) {
            AzureTaskManager.getInstance().runOnPooledThread(() -> this.getRevisionModule().refresh());
        }
        return result;
    }

    @Nonnull
    private com.azure.resourcemanager.appcontainers.models.ContainerApp.Update updateImage(@Nonnull com.azure.resourcemanager.appcontainers.models.ContainerApp origin) {
        final ImageConfig config = Objects.requireNonNull(this.getConfig().getImageConfig(), "image config is null.");
        final com.azure.resourcemanager.appcontainers.models.ContainerApp.Update update = origin.update();
        final ContainerRegistry registry = config.getContainerRegistry();
        final List<Secret> secrets = origin.listSecrets().value().stream().map(s -> new Secret().withName(s.name()).withValue(s.value())).collect(Collectors.toList());
        final List<RegistryCredentials> registries = Optional.ofNullable(origin.configuration().registries()).map(ArrayList::new).orElseGet(ArrayList::new);
        if (Objects.nonNull(registry)) { // update registries and secrets for ACR
            Optional.ofNullable(getSecret(config)).ifPresent(secret -> {
                secrets.removeIf(r -> r.name().equalsIgnoreCase(secret.name()));
                secrets.add(secret);
            });
            Optional.ofNullable(getRegistryCredential(config)).ifPresent(credential -> {
                registries.removeIf(r -> r.server().equalsIgnoreCase(credential.server()));
                registries.add(credential);
            });
        }
        update.withConfiguration(origin.configuration()
            .withRegistries(registries)
            .withSecrets(secrets));
        // drop old containers because we want to replace the old image
        return update.withTemplate(origin.template().withContainers(ImageConfig.toContainers(config)));
    }

    public void buildImageIfNeeded(ImageConfig imageConfig) {
        if (!Optional.ofNullable(imageConfig).map(ImageConfig::getBuildImageConfig).map(b -> b.source).filter(Files::exists).isPresent()) {
            OperationContext.action().setTelemetryProperty("needBuildImage", "false");
            return;
        }
        OperationContext.action().setTelemetryProperty("needBuildImage", "true");
        OperationContext.action().setTelemetryProperty("hasDockerFile", String.valueOf(imageConfig.sourceHasDockerFile()));
        final BuildImageConfig buildConfig = Objects.requireNonNull(imageConfig.getBuildImageConfig());
        final String fullImageName;
        if (imageConfig.sourceHasDockerFile()) {
            // ACR Task is the only way we have for now to build a Dockerfile using Docker.
            AzureMessager.getMessager().warning("Dockerfile detected. Running the build through ACR.");
            final ContainerRegistry registry = getOrCreateRegistry(imageConfig);
            tarSourceIfNeeded(buildConfig);
            final RegistryTaskRun run = registry.buildImage(imageConfig.getAcrImageNameWithTag(), buildConfig.getSource());
            if (Objects.isNull(run)) {
                throw new AzureToolkitRuntimeException("ACR is not ready, Failed to build image through ACR.");
            }
            fullImageName = registry.waitForImageBuilding(run);
        } else {
            OperationContext.action().setTelemetryProperty("isDirectory", String.valueOf(Files.isDirectory(buildConfig.source)));
            if (Files.isDirectory(buildConfig.source)) {
                AzureMessager.getMessager().warning("No Dockerfile detected. Building container image from source code through Container Apps cloud build.");
            } else {
                AzureMessager.getMessager().warning("Building container image from artifact through Container Apps cloud build.");
            }
            final ContainerAppsEnvironment environment = Objects.requireNonNull(this.getManagedEnvironment());
            tarSourceIfNeeded(buildConfig);
            final BuildResource build = environment.buildImage(buildConfig.getSource(), buildConfig.sourceBuildEnv);
            fullImageName = environment.waitForImageBuilding(build);
        }
        if (StringUtils.isNotBlank(fullImageName)) {
            imageConfig.setFullImageName(fullImageName);
        }
    }

    private static void tarSourceIfNeeded(final BuildImageConfig buildConfig) {
        if (Files.isDirectory(buildConfig.source)) {
            final HashSet<String> ignored = Sets.newHashSet(".git", ".gitignore", ".bzr", "bzrignore", ".hg", ".hgignore", ".svn");
            AzureMessager.getMessager().progress(AzureString.format("Creating tar.gz from %s.", buildConfig.source.getFileName()));
            final Path sourceTar = Utils.tar(buildConfig.source, (path) -> ignored.contains(path.getFileName().toString()));
            buildConfig.setSource(sourceTar);
        }
    }

    @Nonnull
    private ContainerRegistry getOrCreateRegistry(final ImageConfig config) {
        ContainerRegistry registry = config.getContainerRegistry();
        if (Objects.isNull(registry)) {
            final String registryName = Objects.requireNonNull(config.getAcrRegistryName());
            final AzureContainerRegistryModule registryModule = Azure.az(AzureContainerRegistry.class)
                .registry(this.getSubscriptionId());
            registry = registryModule.get(registryName, this.getResourceGroupName());
            if (Objects.isNull(registry)) {
                final List<ContainerRegistry> registries = registryModule.listByResourceGroup(this.getResourceGroupName());
                if (!registries.isEmpty()) {
                    registry = registries.stream().filter(ContainerRegistry::isAdminUserEnabled).findAny().orElse(null);
                    if (Objects.isNull(registry)) {
                        registry = registries.stream().findFirst().orElse(null);
                    }
                }
                if (Objects.isNull(registry)) {
                    AzureMessager.getMessager().info(AzureString.format("creating new container registry %s with admin user enabled.", registryName));
                    registry = registryModule.create(registryName, this.getResourceGroupName());
                    final ContainerRegistryDraft draft = (ContainerRegistryDraft) registry;
                    draft.setSku(Sku.Standard);
                    draft.setAdminUserEnabled(true);
                    draft.setRegion(Optional.ofNullable(this.getRegion()).orElse(Region.US_EAST));
                    draft.commit();
                } else {
                    AzureMessager.getMessager().info(AzureString.format("use container registry %s.", registry.getName()));
                }
            }
        }
        if (registry.isDraftForCreating()) {
            ((ContainerRegistryDraft) registry).setAdminUserEnabled(true);
            ((ContainerRegistryDraft) registry).commit();
        } else if (!registry.isAdminUserEnabled()) {// enable admin user
            AzureMessager.getMessager().info(AzureString.format("Enabling admin user for container registry %s.", registry.getName()));
            registry.enableAdminUser();
        }
        config.setContainerRegistry(registry);
        return registry;
    }

    @Nullable
    private static Secret getSecret(final ImageConfig config) {
        final ContainerRegistry registry = config.getContainerRegistry();
        if (Objects.nonNull(registry)) {
            final String password = Optional.ofNullable(registry.getPrimaryCredential()).orElseGet(registry::getSecondaryCredential);
            final String passwordKey = Objects.equals(password, registry.getPrimaryCredential()) ? "password" : "password2";
            final String passwordName = String.format("%s-%s", registry.getName().toLowerCase(), passwordKey);
            return new Secret().withName(passwordName).withValue(password);
        }
        return null;
    }

    @Nullable
    private static RegistryCredentials getRegistryCredential(final ImageConfig config) {
        final ContainerRegistry registry = config.getContainerRegistry();
        if (Objects.nonNull(registry)) {
            final String username = registry.getUserName();
            final String password = Optional.ofNullable(registry.getPrimaryCredential()).orElseGet(registry::getSecondaryCredential);
            final String passwordKey = Objects.equals(password, registry.getPrimaryCredential()) ? "password" : "password2";
            final String passwordName = String.format("%s-%s", registry.getName().toLowerCase(), passwordKey);
            return new RegistryCredentials().withServer(registry.getLoginServerUrl()).withUsername(username).withPasswordSecretRef(passwordName);
        }
        return null;
    }

    @Nonnull
    private synchronized Config ensureConfig() {
        this.config = Optional.ofNullable(this.config).orElseGet(Config::new);
        return this.config;
    }

    @Override
    @Nullable
    public ScaleConfig getScaleConfig() {
        return Optional.ofNullable(config).map(Config::getScaleConfig).orElse(super.getScaleConfig());
    }

    @Override
    @Nullable
    public IngressConfig getIngressConfig() {
        return Optional.ofNullable(config).map(Config::getIngressConfig).orElse(super.getIngressConfig());
    }

    @Override
    @Nullable
    public ImageConfig getImageConfig() {
        return Optional.ofNullable(config).map(Config::getImageConfig).orElse(super.getImageConfig());
    }

    @Override
    @Nullable
    public RevisionMode getRevisionMode() {
        return Optional.ofNullable(config).map(Config::getRevisionMode).orElse(super.getRevisionMode());
    }

    @Nullable
    @Override
    public ContainerAppsEnvironment getManagedEnvironment() {
        return Optional.ofNullable(config).map(Config::getEnvironment).orElseGet(super::getManagedEnvironment);
    }

    @Nullable
    @Override
    public String getManagedEnvironmentId() {
        return Optional.ofNullable(config).map(Config::getEnvironment).map(ContainerAppsEnvironment::getId).orElseGet(super::getManagedEnvironmentId);
    }

    @Nullable
    @Override
    public Region getRegion() {
        return Optional.ofNullable(config).map(Config::getEnvironment).map(ContainerAppsEnvironment::getRegion).orElseGet(super::getRegion);
    }

    @Override
    public boolean isIngressEnabled() {
        return Optional.ofNullable(config).map(Config::getIngressConfig).map(IngressConfig::isEnableIngress).orElseGet(super::isIngressEnabled);
    }

    public ResourceConfiguration getResourceConfiguration() {
        return Optional.ofNullable(config).map(Config::getResourceConfiguration).orElseGet(super::getResourceConfiguration);
    }

    @Override
    public boolean isModified() {
        return this.config == null || Objects.equals(this.config, new Config());
    }

    @Data
    public static class Config {
        private String name;
        private Subscription subscription;
        private ResourceGroup resourceGroup;
        @Nullable
        private ContainerAppsEnvironment environment;
        private RevisionMode revisionMode = RevisionMode.SINGLE;
        @Nullable
        private ImageConfig imageConfig;
        @Nullable
        private IngressConfig ingressConfig;
        @Nullable
        private ScaleConfig scaleConfig;
        @Nullable
        private ResourceConfiguration resourceConfiguration;
    }

    @Setter
    @Getter
    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    public static class ImageConfig {
        @Nonnull
        @EqualsAndHashCode.Include
        private String fullImageName;
        @Nullable
        private ContainerRegistry containerRegistry;
        @Nonnull
        private List<EnvironmentVar> environmentVariables = new ArrayList<>();
        @Nullable
        private BuildImageConfig buildImageConfig;

        public ImageConfig(@Nonnull String fullImageName) {
            this.fullImageName = fullImageName;
        }

        public String getTag() {
            return Optional.of(fullImageName.substring(fullImageName.lastIndexOf(':') + 1)).filter(StringUtils::isNotBlank).orElse("latest");
        }

        public String getRegistryUrl() {
            return fullImageName.substring(0, fullImageName.indexOf('/'));
        }

        @Nullable
        public String getAcrRegistryName() {
            final String registryUrl = this.getRegistryUrl();
            if (registryUrl.endsWith(ACR_IMAGE_SUFFIX)) {
                return registryUrl.substring(0, registryUrl.length() - ACR_IMAGE_SUFFIX.length());
            }
            return null;
        }

        public String getAcrImageNameWithTag() {
            return fullImageName.substring(fullImageName.indexOf('/') + 1);
        }

        public boolean sourceHasDockerFile() {
            return Optional.ofNullable(buildImageConfig).map(BuildImageConfig::sourceHasDockerFile).orElse(false);
        }

        public static List<Container> toContainers(@Nonnull final ImageConfig config) {
            return toContainers(config, null);
        }

        public static List<Container> toContainers(@Nonnull final ImageConfig config, @Nullable ResourceConfiguration resource) {
            final String imageId = config.getFullImageName();
            final String containerName = getContainerNameForImage(imageId);
            // drop old containers because we want to replace the old image
            final Container container = new Container().withName(containerName).withImage(imageId).withEnv(config.getEnvironmentVariables());
            if (Objects.nonNull(resource)) {
                final ContainerResources containerResources = new ContainerResources();
                containerResources.withCpu(resource.getCpu());
                containerResources.withMemory(resource.getMemory());
                container.withResources(containerResources);
            }
            return Collections.singletonList(container);
        }

        private static String getContainerNameForImage(String containerImageName) {
            final String name = containerImageName.substring(containerImageName.lastIndexOf('/') + 1).replaceAll("[^0-9a-zA-Z-]", "-").toLowerCase();
            // The length of container name can not be more than 46.
            return StringUtils.substring(name, 0, 46);
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class BuildImageConfig {
        @Nonnull
        private Path source;
        private Map<String, String> sourceBuildEnv;

        public boolean sourceHasDockerFile() {
            return Optional.of(source)
                .filter(Files::isDirectory)
                .map(p -> Files.isRegularFile(Paths.get(p.toString(), "Dockerfile"))).orElse(false);
        }
    }

    @Getter
    @Builder
    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    public static class ScaleConfig {
        @EqualsAndHashCode.Include
        private Integer maxReplicas;
        @Builder.Default
        @EqualsAndHashCode.Include
        private Integer minReplicas = 1;

        public static Scale toScale(ScaleConfig config) {
            return Optional.ofNullable(config).map(s -> new Scale().withMinReplicas(s.minReplicas).withMaxReplicas(s.maxReplicas)).orElse(null);
        }
    }
}
