package org.congocc.templates.core;

import org.congocc.templates.*;
import java.io.*;
import java.util.HashMap;

/**
 * Command-line utility, the Main-Class of <tt>CTL.jar</tt>.
 * If invoked with no parameters it just prints the version number.
 * If you invoke it with filename, it reads int the file as a template
 * and processes it with an empty data model, sending the output to stdout.
 * 
 * Note that this command-line utility mostly exists as a convenient entry
 * point for debugging/testing when setting up a development environment.
 */
public class CommandLine {
	
	
	public static void main(String[] args) throws IOException {
		if (args.length == 0) {
		    info();
			return;
		}
		processTemplate(args[0]);
	}
	
	/**
	 * processes template with an empty data model
	 * @param filename name of file
	 * @throws IOException
	 */
	public static void processTemplate(String filename) throws IOException {
		File file = new File(filename).getCanonicalFile();
		Configuration conf = new Configuration();
		conf.setDirectoryForTemplateLoading(file.getParentFile().toString());
		Template template = conf.getTemplate(file.getName());
		Writer out = new OutputStreamWriter(System.out);
		template.process(new HashMap<String, Object>(), out);
	}
    
    public static void info() {
        System.out.println();
        System.out.print("Congo Templates version ");
        System.out.println(Configuration.getVersionNumber());
        System.out.println();
        System.out.println("Copyright (c) 2023 Jonathan Revusky.");
        System.out.println("All rights reserved.");
        System.out.println();
        System.out.println("This is Free software. Please read the LICENSE.md");
        System.out.println("for more details.");
        System.out.println();
        System.out.println("For more information and for updates visit:");
        System.out.println("https://discuss.congocc.org/");
        System.out.println();
    }
}