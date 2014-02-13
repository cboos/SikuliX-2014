/*
 * Copyright 2010-2014, Sikuli.org
 * Released under the MIT License.
 *
 * Roman S Samarev 2014
 */
package org.sikuli.scriptrunner;

//import java.io.File;
import org.sikuli.basics.Debug;
import org.sikuli.basics.IScriptRunner;

import java.io.File;
//import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
//import java.io.PrintWriter;
//import java.io.StringWriter;
import java.io.FileReader;

import org.sikuli.basics.FileManager;
import org.sikuli.basics.Settings;
import org.sikuli.basics.SikuliX;

import org.jruby.embed.ScriptingContainer;
import org.jruby.javasupport.JavaEmbedUtils.EvalUnit;
import org.jruby.CompatVersion;
import org.jruby.embed.LocalContextScope;
import org.jruby.RubyInstanceConfig.CompileMode;

public class JRubyScriptRunner implements IScriptRunner {

	//<editor-fold defaultstate="collapsed" desc="new logging concept">
	private static final String me = "JRubyScriptRunner: ";
	private int lvl = 3;

	private void log(int level, String message, Object... args) {
		Debug.logx(level, level < 0 ? "error" : "debug",
						me + message, args);
	}
	//</editor-fold>

	/**
	 * The ScriptingContainer instance
	 */
	private static ScriptingContainer interpreter = null;
	private static int savedpathlen = 0;
	private static final String COMPILE_ONLY = "# COMPILE ONLY";
	/**
	 * sys.argv for the jruby script
	 */
	private static ArrayList<String> sysargv = null;
	/**
	 * The header commands, that are executed before every script
	 */
	private final static String SCRIPT_HEADER
					= "# coding: utf-8\n"
					+ "require 'java'\n"
					+ "require 'rukuli'\n"
					+ "require 'sikulix'\n"
					+ "Rukuli::Config.run do |config|\n"
					+ "  config.image_path = SIKULI_IMAGE_PATH + '/'\n"
					+ "  config.logging = true\n"
					+ "end\n";

	private static ArrayList<String> codeBefore = null;
	private static ArrayList<String> codeAfter = null;
	/**
	 * CommandLine args
	 */
	private int errorLine;
	private int errorColumn;
	private String errorType;
	private String errorText;
	private int errorClass;
	private String errorTrace;

	private static final int PY_SYNTAX = 0;
	private static final int PY_RUNTIME = 1;
	private static final int PY_JAVA = 2;
	private static final int PY_UNKNOWN = -1;

	private static String sikuliLibPath;

	@Override
	public void init(String[] args) {
		//TODO classpath and other path handlings
		sikuliLibPath = new File(SikuliX.getJarPath(), "Lib").getAbsolutePath();
		if (!SikuliX.isRunningFromJar()
						|| !sikuliLibPath.contains("sikuli-ide")
						|| !sikuliLibPath.contains("sikuli-script")) {
			if (System.getProperty("ruby.path") == null) {
				System.setProperty("ruby.path", sikuliLibPath);
				log(lvl, "init: python.path hack: \n" + System.getProperty("ruby.path"));
			} else {
				String currentPath = System.getProperty("ruby.path");
				if (!FileManager.pathEquals(currentPath, sikuliLibPath)) {
					log(-1, "init: Not running from jar and Ruby path not empty: Sikuli might not work!\n"
									+ "Current python.path: " + currentPath);
				}
			}
		}
	}

	@Override
	public int runScript(File scriptfile, File imagedirectory, String[] scriptArgs, String[] forIDE) {
		if (null == scriptfile) {
			//run the Ruby statements from argv (special for setup functional test)
			fillSysArgv(null, null);
			createScriptingContainer();
			interpreter.put("SIKULI_IMAGE_PATH",
							imagedirectory.getAbsolutePath());
			executeScriptHeader(new String[0]);
			SikuliX.displaySplash(null);
			return runRuby(null, scriptArgs, null);
		}
		scriptfile = new File(scriptfile.getAbsolutePath());
		fillSysArgv(scriptfile, scriptArgs);
		createScriptingContainer();
		interpreter.put("SIKULI_IMAGE_PATH",
						imagedirectory.getAbsolutePath());
		if (forIDE == null) {
			executeScriptHeader(new String[]{
				scriptfile.getParentFile().getAbsolutePath(),
				scriptfile.getParentFile().getParentFile().getAbsolutePath()});
		} else {
			executeScriptHeader(new String[]{
				forIDE[0]});
		}
		int exitCode = 0;
		SikuliX.displaySplashFirstTime(null);
		SikuliX.displaySplash(null);
		if (forIDE == null) {
			exitCode = runRuby(scriptfile, null,
							new String[]{scriptfile.getParentFile().getAbsolutePath()});
		} else {
			exitCode = runRuby(scriptfile, null, forIDE);
		}
		log(lvl + 1, "runScript: at exit: path:");
		for (Object p : interpreter.getLoadPaths()) {
			log(lvl + 1, "runScript: " + p.toString());
		}
		log(lvl + 1, "runScript: at exit: --- end ---");
		return exitCode;
	}

	@Override
	public int runTest(File scriptfile, File imagedirectory, String[] scriptArgs, String[] forIDE) {
		throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
	}

	@Override
	public int runInteractive(String[] scriptArgs) {
		throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
	}

	@Override
	public String getCommandLineHelp() {
		throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
	}

	@Override
	public String getInteractiveHelp() {
		throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
	}

	@Override
	public String getName() {
		return "jruby";
	}

	@Override
	public String[] getFileEndings() {
		return new String[]{"rb"};
	}

	@Override
	public String hasFileEnding(String ending) {
		for (String suf : getFileEndings()) {
			if (suf.equals(ending.toLowerCase())) {
				return suf;
			}
		}
		return null;
	}

	@Override
	public void close() {
		if (interpreter != null) {
			interpreter.clear();
		}
	}

	@Override
	public boolean doSomethingSpecial(String action, Object[] args) {
		if ("redirect".equals(action)) {
			doRedirect((PipedInputStream[]) args);
			return true;
		} else if ("convertSrcToHtml".equals(action)) {
			//convertSrcToHtml((String) args[0]);
			return true;
		} else if ("cleanBundle".equals(action)) {
			//cleanBundle((String) args[0]);
			return true;
		} else if ("createRegionForWith".equals(action)) {
			//args[0] = createRegionForWith(args[0]);
			return true;
		} else {
			return false;
		}
	}

	@Override
	public void execBefore(String[] stmts) {
		if (stmts == null) {
			codeBefore = null;
			return;
		}
		if (codeBefore == null) {
			codeBefore = new ArrayList<String>();
		}
		codeBefore.addAll(Arrays.asList(stmts));
	}

	@Override
	public void execAfter(String[] stmts) {
		if (stmts == null) {
			codeAfter = null;
			return;
		}
		if (codeAfter == null) {
			codeAfter = new ArrayList<String>();
		}
		codeAfter.addAll(Arrays.asList(stmts));
	}

	private int runRuby(File ruFile, String[] stmts, String[] scriptPaths) {
		int exitCode = 0;
		String stmt = "";
		boolean fromIDE = false;
		String filename = "<script>";
		try {
			if (null == ruFile) {
				log(lvl, "runRuby: running statements");
				StringBuilder buffer = new StringBuilder();
				for (String e : stmts) {
					buffer.append(e);
				}
				interpreter.setScriptFilename(filename);
				interpreter.runScriptlet(buffer.toString());
			} else {
				filename = ruFile.getAbsolutePath();
				if (scriptPaths != null) {
					FileReader script = new FileReader(ruFile.getAbsolutePath());
// TODO implement compile only !!!
					if (scriptPaths[0].toUpperCase().equals(COMPILE_ONLY)) {
						log(lvl, "runRuby: running COMPILE_ONLY");
						EvalUnit unit = interpreter.parse(script, filename);
						//unit.run();
					} else {
						if (scriptPaths.length > 1) {
							filename = FileManager.slashify(scriptPaths[0], true)
											+ scriptPaths[1] + ".sikuli";
							log(lvl, "runRuby: running script from IDE: \n" + filename);
							fromIDE = true;
						} else {
							filename = scriptPaths[0];
							log(lvl, "runRuby: running script: \n" + filename);
						}
						interpreter.runScriptlet(script, filename);
					}
				} else {
					log(-1, "runRuby: invalid arguments");
					exitCode = -1;
				}
			}
		} catch (Exception e) {
			java.util.regex.Pattern p
							= java.util.regex.Pattern.compile("SystemExit: ([0-9]+)");
			Matcher matcher = p.matcher(e.toString());
//TODO error stop I18N
			if (matcher.find()) {
				exitCode = Integer.parseInt(matcher.group(1));
				Debug.info("Exit code: " + exitCode);
			} else {
				//log(-1,_I("msgStopped"));
				if (null != ruFile) {
					exitCode = findErrorSource(e, filename, scriptPaths);
				} else {
					Debug.error("runRuby: Ruby exception: %s with %s", e.getMessage(), stmt);
				}
				if (fromIDE) {
					exitCode *= -1;
				} else {
					exitCode = 1;
				}
			}
		}
		return exitCode;
	}

	private int findErrorSource(Throwable thr, String filename, String[] forIDE) {
		String err = thr.getMessage();

		errorLine = -1;
		errorColumn = -1;
		errorClass = PY_UNKNOWN;
		errorType = "--UnKnown--";
		errorText = "--UnKnown--";

		String msg;

		if (err.startsWith("(SyntaxError)")) {
            //org.jruby.parser.ParserSyntaxException
			//(SyntaxError) /tmp/sikuli-3213678404470696048.rb:2: syntax error, unexpected tRCURLY

			Pattern pLineS = Pattern.compile("(?<=:)(.*):(.*)");
			java.util.regex.Matcher mLine = pLineS.matcher(err);
			if (mLine.find()) {
				log(lvl + 2, "SyntaxError error line: " + mLine.group(1));
				errorText = mLine.group(2) == null ? errorText : mLine.group(2);
				log(lvl + 2, "SyntaxError: " + errorText);
				errorLine = Integer.parseInt(mLine.group(1));
				errorColumn = -1;
				errorClass = PY_SYNTAX;
				errorType = "SyntaxError";
			}
		} else {
            //if (err.startsWith("(NameError)")) {
			// org.jruby.embed.EvalFailedException
			//(NameError) undefined local variable or method `asdf' for main:Object

			Pattern type = Pattern.compile("(?<=\\()(\\w*)");
			java.util.regex.Matcher mLine = type.matcher(err);
			if (mLine.find()) {
				errorType = mLine.group(1);
			}
			Throwable cause = thr.getCause();
			//cause.printStackTrace();
			for (StackTraceElement line : cause.getStackTrace()) {
				if (line.getFileName().equals(filename)) {
					errorText = cause.getMessage();
					errorColumn = -1;
					errorLine = line.getLineNumber();
					errorClass = PY_RUNTIME;
					this.errorText = thr.getMessage();

					if (errorType.equals("Rukuli::ImageNotFound")) {
						errorType = "FindFailed";
					} else if (errorType.equals("RuntimeError")) {
						errorClass = PY_JAVA;
					}
					//errorType = "NameError";
					break;
				}
			}
		}

		msg = "script";
		if (forIDE != null) {
			msg += " [ " + forIDE[1] + " ]";
		}
		if (errorLine != -1) {
			//log(-1,_I("msgErrorLine", srcLine));
			msg += " stopped with error in line " + errorLine;
			if (errorColumn != -1) {
				msg += " at column " + errorColumn;
			}
		} else {
			msg += "] stopped with error at line --unknown--";
		}

		if (errorClass == PY_RUNTIME || errorClass == PY_SYNTAX) {
			Debug.error(msg);
			Debug.error(errorType + " ( " + errorText + " )");
			if (errorClass == PY_RUNTIME) {
				Throwable cause = thr.getCause();
				//cause.printStackTrace();
				StackTraceElement[] stack = cause.getStackTrace();
				/*StringWriter writer = new StringWriter();
				 PrintWriter out = new PrintWriter(writer);
				 cause.printStackTrace(out);
				 errorTrace = writer.toString();*/
				StringBuilder builder = new StringBuilder();
				for (StackTraceElement line : stack) {
					builder.append(line.getLineNumber());
					builder.append(":\t");
					builder.append(line.getClassName());
					builder.append(" ( ");
					builder.append(line.getMethodName());
					builder.append(" )\t");
					builder.append(line.getFileName());
					builder.append('\n');
				}
				errorTrace = builder.toString();
				if (errorTrace.length() > 0) {
					Debug.error("--- Traceback --- error source first\n"
									+ "line: class ( method ) file \n" + errorTrace
									+ "[error] --- Traceback --- end --------------");
					log(lvl + 2, "--- Traceback --- error source first\n"
									+ "line: class ( method ) file \n" + errorTrace
									+ "[error] --- Traceback --- end --------------");
				}
			}
		} else if (errorClass == PY_JAVA) {
		} else {
			Debug.error(msg);
			Debug.error("Could not evaluate error source nor reason. Analyze StackTrace!");
			Debug.error(err);
		}
		return errorLine;
	}

	/**
	 * Initializes the ScriptingContainer and creates an instance.
	 */
	private void createScriptingContainer() {
//TODO create a specific RubyPath (sys.path)
		if (interpreter == null) {
			//ScriptingContainer.initialize(System.getProperties(), null, sysargv.toArray(new String[0]));

			interpreter = new ScriptingContainer(
							LocalContextScope.THREADSAFE);
			interpreter.setCompatVersion(CompatVersion.RUBY2_0);
			interpreter.setCompileMode(CompileMode.JIT);
		}
	}

	public ScriptingContainer getScriptingContainer() {
		if (interpreter == null) {
			sysargv = new ArrayList<String>();
			sysargv.add("--???--");
			sysargv.addAll(Arrays.asList(Settings.getArgs()));
			createScriptingContainer();
		}
		return interpreter;
	}

	/**
	 * Executes the defined header for the jython script.
	 *
	 * @param syspaths List of all syspath entries
	 */
	private void executeScriptHeader(String[] syspaths) {
// TODO implement compile only
		if (syspaths.length > 0 && syspaths[0].toUpperCase().equals(COMPILE_ONLY)) {
			return;
		}
		List<String> jypath = interpreter.getLoadPaths();
		if (!FileManager.pathEquals((String) jypath.get(0), sikuliLibPath)) {
			log(lvl, "executeScriptHeader: adding SikuliX Lib path to sys.path\n" + sikuliLibPath);
			int jypathLength = jypath.size();
			String[] jypathNew = new String[jypathLength + 1];
			jypathNew[0] = sikuliLibPath;
			for (int i = 0; i < jypathLength; i++) {
				log(lvl + 1, "executeScriptHeader: before: %d: %s", i, jypath.get(i));
				jypathNew[i + 1] = (String) jypath.get(i);
			}
			for (int i = 0; i < jypathLength; i++) {
				jypath.set(i, jypathNew[i]);
			}
			jypath.add(jypathNew[jypathNew.length - 1]);
			for (int i = 0; i < jypathNew.length; i++) {
				log(lvl + 1, "executeScriptHeader: after: %d: %s", i, jypath.get(i));
			}
		}
		if (savedpathlen == 0) {
			savedpathlen = interpreter.getLoadPaths().size();
			log(lvl + 1, "executeScriptHeader: saved sys.path: %d", savedpathlen);
		}
		while (interpreter.getLoadPaths().size() > savedpathlen) {
			interpreter.getLoadPaths().remove(savedpathlen);
		}
		log(lvl + 1, "executeScriptHeader: at entry: path:");
		for (Object p : interpreter.getLoadPaths()) {
			log(lvl + 1, p.toString());
		}
		log(lvl + 1, "executeScriptHeader: at entry: --- end ---");
		for (String syspath : syspaths) {
			jypath.add(FileManager.slashify(syspath, false));
		}

		interpreter.runScriptlet(SCRIPT_HEADER);

		if (codeBefore != null) {
			StringBuilder buffer = new StringBuilder();
			for (String line : codeBefore) {
				buffer.append(line);
			}
			interpreter.runScriptlet(buffer.toString());
		}
	}

	private boolean doRedirect(PipedInputStream[] pin) {
		ScriptingContainer interpreter = getScriptingContainer();
		try {
			PipedOutputStream pout = new PipedOutputStream(pin[0]);
			PrintStream ps = new PrintStream(pout, true);
			System.setOut(ps);
			interpreter.setOutput(ps);
		} catch (Exception e) {
			log(-1, "doRedirect: Couldn't redirect STDOUT\n%s", e.getMessage());
			return false;
		}
		try {
			PipedOutputStream pout = new PipedOutputStream(pin[1]);
			PrintStream ps = new PrintStream(pout, true);
			System.setErr(ps);
			interpreter.setError(ps);
		} catch (Exception e) {
			log(-1, "doRedirect: Couldn't redirect STDERR\n%s", e.getMessage());
			return false;
		}
		return true;
	}

	/**
	 * Fills the sysargv list for the Ruby script
	 *
	 * @param filename The file containing the script: Has to be passed as first
	 * parameter in Ruby
	 * @param argv The parameters passed to Sikuli with --args
	 */
	private void fillSysArgv(File filename, String[] argv) {
		sysargv = new ArrayList<String>();
		if (filename != null) {
			sysargv.add(filename.getAbsolutePath());
		}
		if (argv != null) {
			sysargv.addAll(Arrays.asList(argv));
		}
	}
}
