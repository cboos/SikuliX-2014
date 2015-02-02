/*
 * Copyright 2010-2014, Sikuli.org, sikulix.com
 * Released under the MIT License.
 *
 * modified RaiMan 2012
 */
package org.sikuli.scriptrunner;

import org.sikuli.script.JythonHelper;
import java.io.File;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.python.core.Py;
import org.python.core.PyFunction;
import org.python.core.PyInstance;
import org.python.core.PyList;
import org.python.core.PyObject;
import org.python.core.PyString;
import org.python.util.PythonInterpreter;
import org.python.util.jython;
import org.sikuli.basics.Debug;
import org.sikuli.basics.FileManager;
import org.sikuli.script.RunTime;

/**
 * Executes Sikuliscripts written in Python/Jython.
 */
public class JythonScriptRunner implements IScriptRunner {

  private static RunTime runTime = ScriptRunner.runTime;

	//<editor-fold defaultstate="collapsed" desc="new logging concept">
	private static final String me = "JythonScriptRunner: ";
	private int lvl = 3;
	private void log(int level, String message, Object... args) {
		Debug.logx(level,	me + message, args);
	}
  private void logp(String message, Object... args) {
    if (runTime.runningWinApp) {
      log(0, message, args);
    } else {
      System.out.println(String.format(message, args));
    }
  }
	//</editor-fold>

	/**
	 * The PythonInterpreter instance
	 */
	private PythonInterpreter interpreter = null;
  private JythonHelper helper = null;
  
	private int savedpathlen = 0;
	private static final String COMPILE_ONLY = "# COMPILE ONLY";
	/**
	 * sys.argv for the jython script
	 */
	private ArrayList<String> sysargv = new ArrayList<String>();
	/**
	 * The header commands, that are executed before every script
	 */
	private static String[] SCRIPT_HEADER = new String[]{
		"# -*- coding: utf-8 -*- ",
		"from sikuli import *",
		"use() #resetROI()",
		"setShowActions(False)"
	};

	private ArrayList<String> codeBefore = null;
	private ArrayList<String> codeAfter = null;
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
	private static final String NL = String.format("%n");
	private Pattern pFile = Pattern.compile("File..(.*?\\.py).*?"
					+ ",.*?line.*?(\\d+),.*?in(.*?)" + NL + "(.*?)" + NL);
	//TODO SikuliToHtmlConverter implement in Java
	final static InputStream SikuliToHtmlConverter
					= JythonScriptRunner.class.getResourceAsStream("/scripts/sikuli2html.py");
	static String pyConverter
					= FileManager.convertStreamToString(SikuliToHtmlConverter);
	private String sikuliLibPath = null;
  private boolean isReady = false;
	private boolean isCompileOnly = false;
	private boolean isFromIDE = false;

  @Override
  public void init(String[] param) {
    if (runTime.runningWinApp) {
      runTime.terminate(1, "JythonScriptRunner called in WinApp (packed .exe)");
    }
    if (isReady) {
      return;
    }

    try {
      getInterpreter();
      helper = JythonHelper.set(interpreter);
      helper.getSysPath();
      String fpAPI = null;
			String[] possibleJars = new String[] {"sikulixapi", "API/target/classes", "sikulix.jar"};
			for (String aJar : possibleJars) {
				if (null != (fpAPI = runTime.isOnClasspath(aJar))) {
					break;
				}
			}
			if (null == fpAPI) {
				runTime.terminate(1, "JythonScriptRunner: no sikulix....jar on classpath");
      }
      String fpAPILib = new File(fpAPI, "Lib").getAbsolutePath();
      helper.putSysPath(fpAPILib, 0);
      helper.setSysPath();
      helper.showSysPath();
      interpreter.exec("from sikuli import *");
      log(3, "running Jython %s", interpreter.eval("SIKULIX_IS_WORKING").toString());
    } catch (Exception ex) {
      runTime.terminate(1, "JythonScriptRunner: cannot be initialized:\n%s", ex);
    }
    isReady = true;
  }

	/**
	 * Executes the jythonscript
	 *
	 * @param pyFile The file containing the script
	 * @param fScriptPath The directory containing the images
	 * @param argv The arguments passed by the --args parameter
	 * @param forIDE
	 * @return The exitcode
	 */
	@Override
	public int runScript(File pyFile, File fScriptPath, String[] argv, String[] forIDE) {
		if (null == pyFile) {
			//run the Python statements from argv (special for setup functional test)
			executeScriptHeader(null);
      log(lvl, "runPython: running statements");
      try {
      for (String e : argv) {
        interpreter.exec(e);
      }
      } catch (Exception ex) {
        log(-1, "runPython: raised: %s", ex.getMessage());
        return -1;
      }
			return 0;
		}
		isFromIDE = ! (forIDE == null);
		if (isFromIDE && forIDE.length > 1 && forIDE[0] != null ) {
			isCompileOnly = forIDE[0].toUpperCase().equals(COMPILE_ONLY);
		}
    JythonHelper.get().insertSysPath(fScriptPath);
		pyFile = new File(pyFile.getAbsolutePath());
		fillSysArgv(pyFile, argv);
		int exitCode = 0;
		if (isFromIDE) {
			executeScriptHeader(new String[]{forIDE[0]});
			exitCode = runPython(pyFile, null, forIDE);
      JythonHelper.get().removeSysPath(fScriptPath);
		} else {
			executeScriptHeader(new String[]{
				pyFile.getParent(),
				pyFile.getParentFile().getParent()});
			exitCode = runPython(pyFile, null, new String[]{pyFile.getParentFile().getAbsolutePath()});
		}
		return exitCode;
	}

  private int runPython(File pyFile, String[] stmts, String[] scriptPaths) {
    int exitCode = 0;
    String stmt = "";
    try {
      if (scriptPaths != null) {
// TODO implement compile only
        if (isCompileOnly) {
          log(lvl, "runPython: running COMPILE_ONLY");
          interpreter.compile(pyFile.getAbsolutePath());
        } else {
          if (scriptPaths.length > 1) {
            String scr = FileManager.slashify(scriptPaths[0], true) + scriptPaths[1] + ".sikuli";
            log(lvl, "runPython: running script from IDE: \n" + scr);
            interpreter.exec("sys.argv[0] = \""
                    + scr + "\"");
          } else {
            log(lvl, "runPython: running script: \n" + scriptPaths[0]);
            interpreter.exec("sys.argv[0] = \"" + scriptPaths[0] + "\"");
          }
          interpreter.execfile(pyFile.getAbsolutePath());
        }
      } else {
        log(-1, "runPython: invalid arguments");
        exitCode = -1;
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
        if (null != pyFile) {
          exitCode = findErrorSource(e, pyFile.getAbsolutePath(), scriptPaths);
        } else {
          Debug.error("runPython: Python exception: %s with %s", e.getMessage(), stmt);
        }
        if (isFromIDE) {
          exitCode *= -1;
        } else {
          exitCode = 1;
        }
      }
    }
    return exitCode;
  }

	private int findErrorSource(Throwable thr, String filename, String[] forIDE) {
		String err = thr.toString();
//      log(-1,"------------- Traceback -------------\n" + err +
//              "------------- Traceback -------------\n");
		errorLine = -1;
		errorColumn = -1;
		errorClass = PY_UNKNOWN;
		errorType = "--UnKnown--";
		errorText = "--UnKnown--";

		String msg;
		Matcher mFile = null;

		if (err.startsWith("Traceback")) {
			Pattern pError = Pattern.compile(NL + "(.*?):.(.*)$");
			mFile = pFile.matcher(err);
			if (mFile.find()) {
				log(lvl + 2, "Runtime error line: " + mFile.group(2)
								+ "\n in function: " + mFile.group(3)
								+ "\n statement: " + mFile.group(4));
				errorLine = Integer.parseInt(mFile.group(2));
				errorClass = PY_RUNTIME;
				Matcher mError = pError.matcher(err);
				if (mError.find()) {
					log(lvl + 2, "Error:" + mError.group(1));
					log(lvl + 2, "Error:" + mError.group(2));
					errorType = mError.group(1);
					errorText = mError.group(2);
				} else {
//org.sikuli.core.FindFailed: FindFailed: can not find 1352647716171.png on the screen
					Pattern pFF = Pattern.compile(": FindFailed: (.*?)" + NL);
					Matcher mFF = pFF.matcher(err);
					if (mFF.find()) {
						errorType = "FindFailed";
						errorText = mFF.group(1);
					} else {
						errorClass = PY_UNKNOWN;
					}
				}
			}
		} else if (err.startsWith("SyntaxError")) {
			Pattern pLineS = Pattern.compile(", (\\d+), (\\d+),");
			java.util.regex.Matcher mLine = pLineS.matcher(err);
			if (mLine.find()) {
				log(lvl + 2, "SyntaxError error line: " + mLine.group(1));
				Pattern pText = Pattern.compile("\\((.*?)\\(");
				java.util.regex.Matcher mText = pText.matcher(err);
				mText.find();
				errorText = mText.group(1) == null ? errorText : mText.group(1);
				log(lvl + 2, "SyntaxError: " + errorText);
				errorLine = Integer.parseInt(mLine.group(1));
				errorColumn = Integer.parseInt(mLine.group(2));
				errorClass = PY_SYNTAX;
				errorType = "SyntaxError";
			}
		}

		msg = "script";
		if (forIDE != null) {
			if (forIDE.length > 1) {
				msg += " [ " + forIDE[1] + " ]";
			} else {
				msg += " [ " + forIDE[0] + " ]";
			}
		} else {
			msg += " [ UNKNOWN ]";
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
				errorClass = findErrorSourceWalkTrace(mFile, filename);
				if (errorTrace.length() > 0) {
					Debug.error("--- Traceback --- error source first\n"
									+ "line: module ( function ) statement \n" + errorTrace
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

	private int findErrorSourceWalkTrace(Matcher m, String filename) {
//[error] Traceback (most recent call last):
//File "/var/folders/wk/pcty7jkx1r5bzc5dvs6n5x_40000gn/T/sikuli-tmp3464751893408897244.py", line 2, in
//sub.hello()
//File "/Users/rhocke/NetBeansProjects/RaiManSikuli2012-Script/sub.sikuli/sub.py", line 4, in hello
//print "hello from sub", 1/0
//ZeroDivisionError: integer division or modulo by zero
		Pattern pModule = Pattern.compile(".*/(.*?).py");
		//Matcher mFile = pFile.matcher(etext);
		String mod;
		String modIgnore = "SikuliImporter,";
		StringBuilder trace = new StringBuilder();
		String telem;
		while (m.find()) {
			if (m.group(1).equals(filename)) {
				mod = "main";
			} else {
				Matcher mModule = pModule.matcher(m.group(1));
				mModule.find();
				mod = mModule.group(1);
				if (modIgnore.contains(mod + ",")) {
					continue;
				}
			}
			telem = m.group(2) + ": " + mod + " ( "
							+ m.group(3) + " ) " + m.group(4) + NL;
			//log(lvl,telem);
			trace.insert(0, telem);
//        log(lvl,"Rest of Trace ----\n" + etext.substring(mFile.end()));
		}
		log(lvl + 2, "------------- Traceback -------------\n" + trace);
		errorTrace = trace.toString();
		return errorClass;
	}

	private void findErrorSourceFromJavaStackTrace(Throwable thr, String filename) {
		log(-1, "findErrorSourceFromJavaStackTrace: seems to be an error in the Java API supporting code");
		StackTraceElement[] s;
		Throwable t = thr;
		while (t != null) {
			s = t.getStackTrace();
			log(lvl + 2, "stack trace:");
			for (int i = s.length - 1; i >= 0; i--) {
				StackTraceElement si = s[i];
				log(lvl + 2, si.getLineNumber() + " " + si.getFileName());
				if (si.getLineNumber() >= 0 && filename.equals(si.getFileName())) {
					errorLine = si.getLineNumber();
				}
			}
			t = t.getCause();
			log(lvl + 2, "cause: " + t);
		}
	}

	@Override
	public int runTest(File scriptfile, File imagepath, String[] argv, String[] forIDE) {
		log(-1, "runTest: Sikuli Test Feature is not implemented at the moment");
		return -1;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int runInteractive(String[] argv) {
		String[] jy_args = null;
		String[] iargs = {"-i", "-c",
			"from sikuli import *; ScriptRunner.runningInteractive(); use(); "
			+ "print \"Hello, this is your interactive Sikuli (rules for interactive Python apply)\\n"
			+ "use the UP/DOWN arrow keys to walk through the input history\\n"
			+ "help()<enter> will output some basic Python information\\n"
			+ "... use ctrl-d to end the session\""};
		if (argv != null && argv.length > 0) {
			jy_args = new String[argv.length + iargs.length];
			System.arraycopy(iargs, 0, jy_args, 0, iargs.length);
			System.arraycopy(argv, 0, jy_args, iargs.length, argv.length);
		} else {
			jy_args = iargs;
		}
		jython.main(jy_args);
		return 0;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getCommandLineHelp() {
		return "You are using the Jython ScriptRunner";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getInteractiveHelp() {
		return "**** this might be helpful ****\n"
						+ "-- execute a line of code by pressing <enter>\n"
						+ "-- separate more than one statement on a line using ;\n"
						+ "-- Unlike the iDE, this command window will not vanish, when using a Sikuli feature\n"
						+ "   so take care, that all you need is visible on the screen\n"
						+ "-- to create an image interactively:\n"
						+ "img = capture()\n"
						+ "-- use a captured image later:\n"
						+ "click(img)";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getName() {
    try {
      Class.forName("org.python.util.PythonInterpreter");
    } catch (ClassNotFoundException ex) {
      return null;
    }
		return ScriptRunner.RPYTHON;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String[] getFileEndings() {
		return new String[]{"py"};
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String hasFileEnding(String ending) {
		for (String suf : getFileEndings()) {
			if (suf.equals(ending.toLowerCase())) {
				return suf;
			}
		}
		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void close() {
		if (interpreter != null) {
			interpreter.cleanup();
		}
	}

	/**
	 * Fills the sysargv list for the Python script
	 *
	 * @param pyFile The file containing the script: Has to be passed as first
	 * parameter in Python
	 * @param argv The parameters passed to Sikuli with --args
	 */
	private void fillSysArgv(File pyFile, String[] argv) {
		sysargv = new ArrayList<String>();
		if (pyFile != null) {
			sysargv.add(pyFile.getAbsolutePath());
		}
		if (argv != null) {
			sysargv.addAll(Arrays.asList(argv));
		}
	}

	private PythonInterpreter getInterpreter() {
		if (interpreter == null) {
			sysargv.add("");
			PythonInterpreter.initialize(System.getProperties(), null, sysargv.toArray(new String[0]));
			interpreter = new PythonInterpreter();
		}
		return interpreter;
	}

	@Override
	public boolean doSomethingSpecial(String action, Object[] args) {
		if ("redirect".equals(action)) {
			return doRedirect((PipedInputStream[]) args);
		} else if ("convertSrcToHtml".equals(action)) {
			convertSrcToHtml((String) args[0]);
			return true;
		} else if ("createRegionForWith".equals(action)) {
			args[0] = createRegionForWith(args[0]);
			return true;
		} else if ("checkCallback".equals(action)) {
			return checkCallback(args);
		} else if ("runLoggerCallback".equals(action)) {
			return runLoggerCallback(args);
		} else if ("runObserveCallback".equals(action)) {
			return runObserveCallback(args);
		} else if ("runCallback".equals(action)) {
			return runCallback(args);
		} else {
			return false;
		}
	}

	private boolean checkCallback(Object[] args) {
		PyInstance inst = (PyInstance) args[0];
		String mName = (String) args[1];
		PyObject method = inst.__getattr__(mName);
		if (method == null || !method.getClass().getName().contains("PyMethod")) {
  		log(-100, "checkCallback: Object: %s, Method not found: %s", inst, mName);
			return false;
		}
		return true;
	}

	private boolean runLoggerCallback(Object[] args) {
		PyInstance inst = (PyInstance) args[0];
		String mName = (String) args[1];
		String msg = (String) args[2];
		PyObject method = inst.__getattr__(mName);
		if (method == null || !method.getClass().getName().contains("PyMethod")) {
  		log(-100, "runLoggerCallback: Object: %s, Method not found: %s", inst, mName);
			return false;
		}
		try {
			PyString pmsg = new PyString(msg);
			inst.invoke(mName, pmsg);
		} catch (Exception ex) {
			log(-100, "runLoggerCallback: invoke: %s", ex.getMessage());
			return false;
		}
		return true;
	}

  private boolean runObserveCallback(Object[] args) {
    PyFunction func = (PyFunction) args[0];
    boolean success = true;
		try {
      ((PyFunction) args[0]).__call__(Py.java2py(args[1]));
		} catch (Exception ex) {
      if (!"<lambda>".equals(func.__name__)) {
        log(-1, "runObserveCallback: jython invoke: %s", ex.getMessage());
        return false;
      }
			success = false;
		}
    if (success) {
      return true;
    }
		try {
      ((PyFunction) args[0]).__call__();
		} catch (Exception ex) {
      log(-1, "runObserveCallback: jython invoke <lambda>: %s", ex.getMessage());
      return false;
    }
    return true;
	}

//TODO implement generalized callback
  private boolean runCallback(Object[] args) {
		PyInstance inst = (PyInstance) args[0];
		String mName = (String) args[1];
		PyObject method = inst.__getattr__(mName);
		if (method == null || !method.getClass().getName().contains("PyMethod")) {
  		log(-1, "runCallback: Object: %s, Method not found: %s", inst, mName);
			return false;
		}
		try {
			PyString pmsg = new PyString("not yet supported");
			inst.invoke(mName, pmsg);
		} catch (Exception ex) {
			log(-1, "runCallback: invoke: %s", ex.getMessage());
			return false;
		}
		return true;
	}

//TODO revise the before/after concept (to support IDE reruns)
	/**
	 * {@inheritDoc}
	 */
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

	/**
	 * {@inheritDoc}
	 */
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

	/**
	 * Executes the defined header for the jython script.
	 *
	 * @param syspaths List of all syspath entries
	 */
	private void executeScriptHeader(String[] syspaths) {
// TODO implement compile only
    for (String line : SCRIPT_HEADER) {
			log(lvl + 1, "executeScriptHeader: %s", line);
			interpreter.exec(line);
		}
		if (codeBefore != null) {
			for (String line : codeBefore) {
				interpreter.exec(line);
			}
		}
		if (isCompileOnly) {
			return;
		}
		PyList jyargv = interpreter.getSystemState().argv;
		jyargv.clear();
		for (String item : sysargv) {
			jyargv.add(item);
		}
	}

	private boolean doRedirect(PipedInputStream[] pin) {
		PythonInterpreter py = getInterpreter();
		Debug.saveRedirected(System.out, System.err);
		try {
			PipedOutputStream pout = new PipedOutputStream(pin[0]);
			PrintStream ps = new PrintStream(pout, true);
      if (!ScriptRunner.systemRedirected) {
  			System.setOut(ps);
      }
			py.setOut(ps);
		} catch (Exception e) {
			log(-1, "%s: redirect STDOUT: %s", getName(), e.getMessage());
			return false;
		}
		try {
			PipedOutputStream eout = new PipedOutputStream(pin[1]);
			PrintStream eps = new PrintStream(eout, true);
      if (!ScriptRunner.systemRedirected) {
        System.setErr(eps);
      }
			py.setErr(eps);
		} catch (Exception e) {
			log(-1, "%s: redirect STDERR: %s", getName(), e.getMessage());
			return false;
		}
		return true;
	}

	private void convertSrcToHtml(String bundle) {
		PythonInterpreter py = new PythonInterpreter();
		log(lvl, "Convert Sikuli source code " + bundle + " to HTML");
		py.set("local_convert", true);
		py.set("sikuli_src", bundle);
		py.exec(pyConverter);
	}

	private Object createRegionForWith(Object reg) {
		return null;
	}
}
