package org.jshybugger;

import java.io.IOException;
import java.io.InputStream;


/**
 * The JsHybugger helper class.
 */
public class JsHybugger {

	/**
	 * Gets the jsHybugger js code.
	 *
	 * @return the source
	 * @throws IOException 
	 */
	public static InputStream getSource() throws IOException {
			
		// for development
		InputStream res = JsHybugger.class.getResourceAsStream("/jshybugger.js");
		if (res != null) {
			return res;
		}
		
		return JsHybugger.class.getResourceAsStream("/jshybugger.min.js");
	}
}
