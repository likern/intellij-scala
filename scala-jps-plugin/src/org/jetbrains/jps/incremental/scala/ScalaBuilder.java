package org.jetbrains.jps.incremental.scala;

import com.intellij.execution.process.BaseOSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.PathUtil;
import com.intellij.util.SystemProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.ModuleChunk;
import org.jetbrains.jps.builders.ChunkBuildOutputConsumer;
import org.jetbrains.jps.builders.DirtyFilesHolder;
import org.jetbrains.jps.builders.FileProcessor;
import org.jetbrains.jps.builders.java.JavaSourceRootDescriptor;
import org.jetbrains.jps.incremental.*;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.incremental.scala.model.FacetSettings;
import org.jetbrains.jps.incremental.scala.model.LibraryLevel;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.java.JpsJavaSdkType;
import org.jetbrains.jps.model.library.JpsLibrary;
import org.jetbrains.jps.model.library.JpsLibraryCollection;
import org.jetbrains.jps.model.library.JpsOrderRootType;
import org.jetbrains.jps.model.library.sdk.JpsSdk;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.service.SharedThreadPool;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Future;

import static com.intellij.openapi.util.io.FileUtil.*;
import static com.intellij.openapi.util.text.StringUtil.*;
import static org.jetbrains.jps.incremental.scala.Utilities.*;

/**
 * @author Pavel Fatin
 */
public class ScalaBuilder extends ModuleLevelBuilder {
  public static final String BUILDER_NAME = "scala";
  public static final String BUILDER_DESCRIPTION = "Scala builder";
  private static Class RUNNER_CLASS = ClassRunner.class;

  public static final String SCALA_EXTENSION = "scala";

  protected ScalaBuilder() {
    super(BuilderCategory.TRANSLATOR);
  }

  private static boolean isScalaFile(String path) {
    return path.endsWith(SCALA_EXTENSION);
  }

  @NotNull
  @Override
  public String getPresentableName() {
    return BUILDER_DESCRIPTION;
  }

  @Override
  public ExitCode build(CompileContext context,
                        ModuleChunk chunk,
                        DirtyFilesHolder<JavaSourceRootDescriptor, ModuleBuildTarget> dirtyFilesHolder,
                        ChunkBuildOutputConsumer outputConsumer) throws ProjectBuildException, IOException {
    ExitCode exitCode;
    try {
      exitCode = doBuild(context, chunk, dirtyFilesHolder);
    } catch (ConfigurationException e) {
      CompilerMessage message = new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.ERROR, e.getMessage());
      context.processMessage(message);
      return ExitCode.ABORT;
    }
    return exitCode;
  }

  private ExitCode doBuild(final CompileContext context, ModuleChunk chunk,
                           DirtyFilesHolder<JavaSourceRootDescriptor, ModuleBuildTarget> dirtyFilesHolder) throws IOException {
    List<File> filesToCompile = collectFilesToCompiler(dirtyFilesHolder);

    if (filesToCompile.isEmpty()) {
      return ExitCode.NOTHING_DONE;
    }

    // Create a temp file for compiler arguments
    File tempFile = FileUtil.createTempFile("ideaScalaToCompile", ".txt", true);

    // Format compiler arguments
    CompilerSettings settings = getCompilerSettings(context, chunk);
    List<String> arguments = createCompilerArguments(settings, filesToCompile);

    // Save the compiler arguments to the temp file
    writeStringTo(tempFile, join(arguments, "\n"));

    List<String> vmClasspath = getVMClasspathIn(context, chunk);

    List<String> commands = ExternalProcessUtil.buildJavaCommandLine(
        getJavaExecutableIn(chunk),
        RUNNER_CLASS.getName(),
        Collections.<String>emptyList(), vmClasspath,
        Arrays.asList("-Xmx384m", "-Dfile.encoding=" + System.getProperty("file.encoding")),
        Arrays.<String>asList("com.typesafe.zinc.Main", tempFile.getPath())
    );

    exec(ArrayUtil.toStringArray(commands), context);

    asyncDelete(tempFile);

    return ExitCode.OK;
  }

  private void exec(String[] commands, final MessageHandler messageHandler) throws IOException {
    Process process = Runtime.getRuntime().exec(commands);

    BaseOSProcessHandler handler = new BaseOSProcessHandler(process, null, null) {
      @Override
      protected Future<?> executeOnPooledThread(Runnable task) {
        return SharedThreadPool.getInstance().executeOnPooledThread(task);
      }
    };

    final OutputParser parser = new OutputParser(messageHandler, BUILDER_NAME);

    handler.addProcessListener(new ProcessAdapter() {
      @Override
      public void onTextAvailable(ProcessEvent event, Key outputType) {
        messageHandler.processMessage(new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.WARNING, event.getText()));
//        parser.processMessageLine(event.getText());
      }

      @Override
      public void processTerminated(ProcessEvent event) {
//        parser.finishProcessing();
      }
    });

    handler.startNotify();
    handler.waitFor();
  }

  private static List<String> createCompilerArguments(CompilerSettings settings,
                                                      Collection<File> sources) {
    List<String> args = new ArrayList<String>();

    args.add("-debug");

    args.add("-scala-path");
    args.add(join(toCanonicalPaths(settings.getCompilerClasspath()), File.pathSeparator));

    args.add("-sbt-interface");
    args.add(toCanonicalPath(settings.getSbtInterfaceFile()));

    args.add("-compiler-interface");
    args.add(toCanonicalPath(settings.getCompilerInterfaceFile()));

    args.add("-d");
    args.add(toCanonicalPath(settings.getOutputDirectory()));

    args.add("-cp");
    args.add(join(toCanonicalPaths(settings.getClasspath()), File.pathSeparator));

    args.addAll(toCanonicalPaths(sources));

    return args;
  }

  private static CompilerSettings getCompilerSettings(CompileContext context, ModuleChunk chunk) {
    JpsModule module = chunk.representativeTarget().getModule();
    JpsModel model = context.getProjectDescriptor().getModel();

    // Find a Scala compiler library that is configured in a Scala facet
    JpsLibrary compilerLibrary = getCompilerLibraryIn(module, model);

    // Collect all files in the compiler library
    Collection<File> compilerLibraryFiles = compilerLibrary.getFiles(JpsOrderRootType.COMPILED);
    if (compilerLibraryFiles.isEmpty()) throw new ConfigurationException(
        "Compiler library is empty: " + compilerLibrary.getName());

    Collection<File> zincFiles = getZincFiles();

    // Find a path to "sbt-interface.jar"
    File sbtInterfaceFile = findByName(zincFiles, "sbt-interface.jar");
    if (sbtInterfaceFile == null) throw new ConfigurationException(
        "No sbt-interface.jar found");

    // Find a path to "compiler-interface-sources.jar"
    File compilerInterfaceFile = findByName(zincFiles, "compiler-interface-sources.jar");
    if (compilerInterfaceFile == null) throw new ConfigurationException(
        "No compiler-interface-sources.jar found");

    ModuleBuildTarget target = chunk.representativeTarget();

    // Get an output directory
    File outputDirectory = target.getOutputDir();
    if (outputDirectory == null) throw new ConfigurationException(
        "Output directory not specified for module " + target.getModuleName());

    // Get compilation classpath files
    Collection<File> chunkClasspath = context.getProjectPaths()
        .getCompilationClasspathFiles(chunk, chunk.containsTests(), false, false);

    return new CompilerSettings(compilerLibraryFiles, sbtInterfaceFile, compilerInterfaceFile,
        outputDirectory, chunkClasspath);
  }

  private static String getJavaExecutableIn(ModuleChunk chunk) {
    JpsSdk<?> sdk = chunk.getModules().iterator().next().getSdk(JpsJavaSdkType.INSTANCE);
    return sdk == null ? SystemProperties.getJavaHome() + "/bin/java" : JpsJavaSdkType.getJavaExecutable(sdk);
  }

  // Collect JVM classpath jars
  private static List<String> getVMClasspathIn(CompileContext context, ModuleChunk chunk) {
    List<String> result = new ArrayList<String>();

    // Add Zinc jars to the classpath
    result.addAll(toCanonicalPaths(getZincFiles()));

    // Add this jar (which contatins a runner class) to the classpath
    result.add(FileUtil.toCanonicalPath(getThisJarFile().getPath()));

    return result;
  }

  // Find Zinc jars
  private static Collection<File> getZincFiles() {
    File zincHomeDirectory = getZincHomeDirectory();

    File[] zincJars = zincHomeDirectory.listFiles();
    if (zincJars == null || zincJars.length == 0) throw new ConfigurationException(
        "No Zinc jars in the directory: " + zincHomeDirectory);

    return Arrays.asList(zincJars);
  }

  private static File getZincHomeDirectory() {
    return new File(getThisJarFile().getParentFile().getParentFile(), "zinc");
  }

  private static File getThisJarFile() {
    return new File(PathUtil.getJarPathForClass(RUNNER_CLASS));
  }

  private static JpsLibrary getCompilerLibraryIn(JpsModule module, JpsModel model) {
    FacetSettings settings = SettingsManager.getFacetSettings(module);

    if (settings == null) throw new ConfigurationException(
        "No Scala facet in module: " + module.getName());

    LibraryLevel compilerLibraryLevel = settings.getCompilerLibraryLevel();

    if (compilerLibraryLevel == null) throw new ConfigurationException(
        "No compiler library level set in module: " + module.getName());

    JpsLibraryCollection libraryCollection = getLibraryCollection(compilerLibraryLevel, model, module);

    String compilerLibraryName = settings.getCompilerLibraryName();

    if (compilerLibraryName == null) throw new ConfigurationException(
        "No compiler library name set in module: " + module.getName());

    JpsLibrary library = libraryCollection.findLibrary(compilerLibraryName);

    if (library == null) throw new ConfigurationException(
        String.format("Сompiler library for module %s not found: %s / %s ",
            module.getName(), compilerLibraryLevel, compilerLibraryName));

    return library;
  }

  private static JpsLibraryCollection getLibraryCollection(LibraryLevel level, JpsModel model, JpsModule module) {
    switch (level) {
      case Global:
        return model.getGlobal().getLibraryCollection();
      case Project:
        return model.getProject().getLibraryCollection();
      case Module:
        return module.getLibraryCollection();
      default:
        throw new ConfigurationException("Unknown library level: " + level);
    }
  }

  private List<File> collectFilesToCompiler(DirtyFilesHolder<JavaSourceRootDescriptor, ModuleBuildTarget> dirtyFilesHolder) throws IOException {
    final List<File> filesToCompile = new ArrayList<File>();

    dirtyFilesHolder.processDirtyFiles(new FileProcessor<JavaSourceRootDescriptor, ModuleBuildTarget>() {
      public boolean apply(ModuleBuildTarget target, File file, JavaSourceRootDescriptor sourceRoot) throws IOException {
        if (isScalaFile(file.getPath())) {
          filesToCompile.add(file);
        }
        return true;
      }
    });

    return filesToCompile;
  }
}
