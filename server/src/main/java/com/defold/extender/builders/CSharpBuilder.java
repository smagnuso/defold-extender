package com.defold.extender.builders;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.ProcessExecutor;
import com.defold.extender.TemplateExecutor;

public class CSharpBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(CSharpBuilder.class);

    private static final String DOTNET_ROOT = System.getenv("DOTNET_ROOT");
    private static final String DOTNET_CLI_HOME = System.getenv("DOTNET_CLI_HOME");
    private static final String DOTNET_VERSION_FILE = System.getenv("DOTNET_VERSION_FILE");
    private static final String NUGET_PACKAGES = System.getenv("NUGET_PACKAGES");

    private List<String>        engineLibs;
    private File                sourceDir;
    private File                csProject;
    private File                outputDir;
    private String              outputName;
    private File                outputFile;
    private String              platform;
    private String              template;
    private ProcessExecutor     processExecutor;
    private TemplateExecutor    templateExecutor;
    private Map<String, Object> context;

    private static String readFile(String filePath) throws IOException {
        if (filePath == null) {
            return "";
        }

        return new String( Files.readAllBytes( Paths.get(filePath) ) );
    }

    public CSharpBuilder(ProcessExecutor processExecutor, TemplateExecutor templateExecutor,
                        Map<String, Object> context) throws IOException {
        this.processExecutor = processExecutor;
        this.templateExecutor = templateExecutor;
        Resource csProjectResource = new ClassPathResource("template.csproj");
        this.template = ExtenderUtil.readContentFromResource(csProjectResource);
        this.context = context;

        LOGGER.info(String.format("DOTNET_CLI_HOME: %s", DOTNET_CLI_HOME));
        LOGGER.info(String.format("NUGET_PACKAGES: %s", NUGET_PACKAGES));
    }

    public void setSourceDirectory(File sourceDir) {
        this.sourceDir = sourceDir;
    }

    public void setEngineLibraries(List<String> engineLibs) {
        this.engineLibs = engineLibs;
    }

    public void setOutputDirectory(File outputDir) {
        this.outputDir = outputDir;
    }

    public void setOutputName(String outputName) {
        this.outputName = outputName;
    }

    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public void setSdkProject(File csProject) {
        this.csProject = csProject;
    }

    private File writeProject() throws IOException {

        context.put("PINVOKE", engineLibs);
        context.put("BUILDDIR_CS", outputDir);
        context.put("DMSDK_CSPROJ", csProject.getAbsolutePath());

        String projectText = templateExecutor.execute(this.template, context);

        File f = new File(this.sourceDir, String.format("%s.csproj", outputName));

        FileUtils.writeStringToFile(f, projectText, Charset.defaultCharset(), true);

        if (!f.exists())
            throw new IOException(String.format("Failed to write to %s", f.getAbsolutePath()));

        LOGGER.info(String.format("Wrote %s file\n", f.getAbsolutePath()));

        return f;
    }

    // https://learn.microsoft.com/en-us/dotnet/core/deploying/native-aot/?tabs=net8plus%2Cwindows#platformarchitecture-restrictions
    private static String convertPlatform(String platform) {
        if (platform.equals("arm64-osx"))       return "osx-arm64";
        if (platform.equals("x86_64-osx"))      return "osx-x64";
        if (platform.equals("arm64-android"))   return "android-arm64";
        if (platform.equals("armv7-android"))   return "android-arm32";
        if (platform.equals("arm64-win32"))     return "win-arm64";
        if (platform.equals("x86_64-win32"))    return "win-x64";
        if (platform.equals("x86-win32"))       return "win-x86";
        if (platform.equals("x86_64-linux"))    return "linux-x64";
        if (platform.equals("x86_64-ios"))      return "ios-x64";
        if (platform.equals("arm64-ios"))       return "ios-arm64";
        return "unknown_platform";
    }

    private boolean isWindows(String platform) {
        if (platform.endsWith("win32"))
            return true;
        return false;
    }

    private File runDotnet(File project, String platform) throws IOException, InterruptedException, ExtenderException {

        if (DOTNET_CLI_HOME == null) {
            throw new ExtenderException("DOTNET_CLI_HOME is not setup correctly! Cannot build C#.");
        }

        String csplatform = convertPlatform(this.platform);
        String cmd = String.format("%s/dotnet publish --nologo -c Release -r %s ", DOTNET_CLI_HOME, csplatform);
        cmd += project.getAbsolutePath();

        List<String> commands = new ArrayList<>();
        commands.add(cmd);
        ProcessExecutor.executeCommands(processExecutor, commands); // in parallel

        String libName;
        if (isWindows(platform))
            libName = String.format("%s.lib", outputName);
        else
            libName = String.format("lib%s.a", outputName);

        File csOutput = new File(this.outputDir, csplatform);
        File csPublish = new File(csOutput, "publish");
        File publishLibrary = new File(csPublish, libName);

        FileUtils.moveFile(publishLibrary, outputFile);
        return outputFile;
    }

    // Build and return the library file(s)
    public List<File> build() throws IOException, InterruptedException, ExtenderException {

        File project = writeProject();

        File library = runDotnet(project, this.platform);

        List<File> out = new ArrayList<>();
        out.add(library);
        return out;
    }

    private static Path getNativePath(String platform) throws IOException {
        String csplatform = convertPlatform(platform);
        String dotnetVersion = readFile(DOTNET_VERSION_FILE).trim();
        return Paths.get(NUGET_PACKAGES, String.format("microsoft.netcore.app.runtime.nativeaot.%s/%s/runtimes/%s/native", csplatform, dotnetVersion, csplatform));
    }

    private static void addLibFlags(String platform, List<String> linkFlags) throws IOException {
        Path aotBase = getNativePath(platform);

        linkFlags.add(Paths.get(aotBase.toString(), "libbootstrapperdll.o").toString());

        // Note: These libraries are specified with full paths, or the linker will link against the dynamic libraries.
        // We want to avoid that hassle for now. Let's do that in a step two.

        // TODO: Do we need a way to toggle these behaviors on/off?
        linkFlags.add(Paths.get(aotBase.toString(), "libRuntime.WorkstationGC.a").toString());
        linkFlags.add(Paths.get(aotBase.toString(), "libeventpipe-enabled.a").toString());
        linkFlags.add(Paths.get(aotBase.toString(), "libstandalonegc-enabled.a").toString());

        linkFlags.add(Paths.get(aotBase.toString(), "libSystem.Native.a").toString());
        linkFlags.add(Paths.get(aotBase.toString(), "libSystem.IO.Compression.Native.a").toString());
        linkFlags.add(Paths.get(aotBase.toString(), "libSystem.Globalization.Native.a").toString());

        if (platform.equals("arm64-osx") || platform.equals("x86_64-osx"))
        {
            linkFlags.add(Paths.get(aotBase.toString(), "libRuntime.VxsortEnabled.a").toString());

        }
        else if (platform.equals("arm64-ios") || platform.equals("x86_64-ios"))
        {
            linkFlags.add(Paths.get(aotBase.toString(), "libstdc++compat.a").toString());
            linkFlags.add(Paths.get(aotBase.toString(), "libSystem.Net.Security.Native.a").toString());
            linkFlags.add(Paths.get(aotBase.toString(), "libSystem.Security.Cryptography.Native.Apple.a").toString());
            linkFlags.add("-licucore");
        }
    }

    public static void updateContext(String platform, Map<String, Object> context) throws IOException {
        Path aotBase = getNativePath(platform);

        List<String> libPaths = (List<String>)context.getOrDefault("libPaths", new ArrayList<String>());
        libPaths.add(aotBase.toString()); // -L/path/to/aot
        context.put("libPaths", libPaths);

        List<String> linkFlags = (List<String>)context.getOrDefault("linkFlags", new ArrayList<String>());
        CSharpBuilder.addLibFlags(platform, linkFlags);
        context.put("linkFlags", linkFlags);
    }

}
