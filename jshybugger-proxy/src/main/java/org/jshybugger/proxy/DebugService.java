/*
 * Copyright 2013 Wolfgang Flohr-Hochbichler (wflohr@jshybugger.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jshybugger.proxy;

import java.io.IOException;

import org.jshybugger.server.DebugServer;
import org.jshybugger.server.DebugSession;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

/**
 * The Class DebugService.
 */
public class DebugService extends Service {

	private DebugSession debugSession;

	private JSDInterface browserInterface;

	private DebugServer debugServer;
	
	/** The logging TAG */
	private static final String TAG = "DebugService";
	
    /* (non-Javadoc)
     * @see android.app.Service#onBind(android.content.Intent)
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


	/* (non-Javadoc)
	 * @see android.app.Service#onCreate()
	 */
	@Override
	public void onCreate() {
		Log.d(TAG, "onCreate()");
		try {
			int debugPort = 8889;
			
			ServiceInfo info = getApplicationContext().getPackageManager().getServiceInfo(new ComponentName(getApplicationContext(), DebugService.class), PackageManager.GET_SERVICES|PackageManager.GET_META_DATA);
			Bundle metaData = info.metaData;
			if (metaData != null) {
				if (metaData.getInt("debugPort", 0) > 0) {
					debugPort = metaData.getInt("debugPort");
				} 
			}		
			debugServer = new DebugServer( debugPort );
			
			debugSession = new ProxyDebugSession(this);
			browserInterface = new JSDInterface();
			
			debugServer.addHandler("/jshybugger/.*", new JSHybuggerResourceHandler(browserInterface));
			debugSession.setBrowserInterface(browserInterface);
			
			debugServer.exportSession(debugSession);
			LogActivity.addMessage("DebugServer listening on port " + debugPort);			

		} catch (IOException ie) {
			LogActivity.addMessage("Starting DebugServer failed: " + ie.toString());			
			Log.e(TAG, "onCreate() failed", ie);
		} catch (InterruptedException e) {
			LogActivity.addMessage("Starting DebugServer failed: " + e.toString());			
			Log.e(TAG, "DebugService.onCreate() failed", e);
		} catch (NameNotFoundException e) {
			LogActivity.addMessage("Starting DebugServer failed: " + e.toString());			
			Log.e(TAG, "DebugService.onCreate() failed", e);
		}
	}
	
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		if (browserInterface != null) {
			browserInterface.stop();
		}
		if (debugServer != null) {
			debugServer.stop();
		}
		LogActivity.addMessage("DebugServer stopped");			
	}


	/* (non-Javadoc)
	 * @see android.app.Service#onStart(android.content.Intent, int)
	 */
	@Override
	public void onStart(Intent intent, int startid) {
		//Log.d(TAG, "onStart: "+ intent);
		broadcastStartup(intent);
	}


	private void broadcastStartup(Intent intent) {
		if ((intent != null) && (intent.getExtras() != null) && (intent.getExtras().get("callback") != null)) {
			try {
				((Messenger) intent.getExtras().get("callback")).send(Message.obtain(null, org.jshybugger.DebugService.MSG_WEBVIEW_ATTACHED));
			} catch (RemoteException e) {
				Log.e(TAG, "Notify client failed", e);
			}
		}
	}

	
	/* (non-Javadoc)
	 * @see android.app.Service#onStartCommand(android.content.Intent, int, int)
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		
		Log.d(TAG, "onStartCommand: " + intent);
		broadcastStartup(intent);

		return START_STICKY;
	}
}
