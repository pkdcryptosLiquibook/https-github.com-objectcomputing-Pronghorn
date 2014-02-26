//Copyright 2013, Nathan Tippy
//See LICENSE file for BSD license details.
//Send support requests to http://www.ociweb.com/contact
package com.ociweb.jfast.stream;

import com.google.caliper.runner.CaliperMain;

public class RunCaliper {

	public static void main(String[] args) {

	//TODO: batch the PMap write bits and send them all at close/open to avoid cache misses.	
		
	//run(HomogeniousRecordWriteReadDecimalBenchmark.class); 
	run(HomogeniousFieldWriteReadIntegerBenchmark.class);
	//run(HomogeniousRecordWriteReadIntegerBenchmark.class); 
    //run(HomogeniousRecordWriteReadLongBenchmark.class); 
    //run(HomogeniousRecordWriteReadTextBenchmark.class);
    
	}

	private static void run(Class clazz) {
		String[] args;
		//  -XX:+UseNUMA
		args = new String[]{"-r",clazz.getSimpleName(),
				             "-Cinstrument.micro.options.warmup=1s"};
		
		//-h
		//-C 5ms
		//--verbose
		
		CaliperMain.main(clazz, args);
	}

}
