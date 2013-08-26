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

/* 
 * JsHybugger runtime library.
 */
if (window['JsHybuggerConfig'] === undefined)
{
	window.JsHybuggerConfig = {
		endpoint: 'http://localhost:8889/jshybugger/'
	};		
}

if (window['JsHybugger'] === undefined) {
window.JsHybugger = (function() {
	var breakpoints = {},
		breakpointsById = {},
		shouldBreak = function() { return false; },
		lastFile = '',
		lastLine = '',
		callStack = [],
		callStackDepth = 0,
		blockModeActive = false,
		globalWatches = {},
		objectGroups = {},
		continueToLocation,
		breakpointsActive = true,
		pauseOnExceptionsState = 'none',
		FRAME_ID = String(new Date().getTime() % 3600000),
		PROTOCOL = 'content://jsHybugger.org/',
		localConsole = {},
		url = JsHybuggerConfig.endpoint;
	
	/**
	 * If native interface is not present, define HTTP interface 
	 */	
	if (window['JsHybuggerNI'] === undefined) {
		console.info("JsHybugger loaded outside a native app.");
		window['JsHybuggerNI'] = {
			sendToDebugService : function(method, data) {
				
				sendXmlData('sendToDebugService', { arg0: method, arg1: data});
			},
			
			sendReplyToDebugService : function(id, data) {

				return sendXmlData('sendReplyToDebugService', { arg0: id, arg1: data});
			},
			
			getQueuedMessage : function(flag) {
				var data = sendXmlData('getQueuedMessage', { arg0: flag});
				
				return data;
			}
		};
		
		openPushChannel();
	}

	/**
	 * Opens channel to the server a listen for notifications. 
	 */
    function openPushChannel() {

		var pushChannel = new XMLHttpRequest();
		pushChannel.onreadystatechange = function() {
//			console.log((new Date()) + "openPushChannel: " + pushChannel.readyState + ", status: " + pushChannel.status + ", text: " + pushChannel.responseText);
			if(pushChannel.readyState == 3) {
				if (pushChannel.responseText) {
					eval(pushChannel.responseText);
				}
			} else if (pushChannel.readyState == 4) {
				setTimeout(openPushChannel, 0);
			}
		};
		try {
			pushChannel.open('GET', url + Math.random() + 'pushChannel', true);
			pushChannel.send();
		} catch (e) {
			//console.log("openPushChannel failed: " + e);
		}
	}
    
    /**
     * Send HTTP request to debug server
	 * @param {string} cmd command string
	 * @param {string} data message content
     * 
     */
    function sendXmlData(cmd, data) {
		var response,
			xmlObj = new XMLHttpRequest();
			
		xmlObj.onreadystatechange = function() {
	//		console.log((new Date()) + "sendXmlData: " + xmlObj.readyState + ", status: " + xmlObj.status + ", text: " + xmlObj.responseText);
			if(xmlObj.readyState == 4) {
				response = xmlObj.status == '200' && xmlObj.responseText && xmlObj.responseText.length > 0 ? xmlObj.responseText : null;
			}
		};
		try {
			xmlObj.open ('POST', url + cmd, false);
			xmlObj.send (stringifySafe(data));
		} catch (e) {
			//console.log("sendXmlData duration: " + (new Date().getTime() - startDate.getTime()) + "ms, " + cmd + " failed: " + e);
		}
		if (response && cmd != 'Console.messageAdded') {
		//	console.log((new Date()) + "sendXmlData: " + response);
		}
		return response;
    }
    
	/**
	 * Processes pending debugger queue messages.
	 * @param {boolean} block call will block until break-able message is received
	 */
    function processMessages(block) {
		if (!block && blockModeActive) return;
		
		var msg = null;
		if (block) {
			try {
				blockModeActive = true;
				while ((msg = JsHybuggerNI.getQueuedMessage(true))) {
					if (!processCommand(parseSafe(msg))) {
						break;
					}
				}
			} finally {
				blockModeActive = false;
			}
		}
		
		// before returning process all pending queue messages
		while ((msg = JsHybuggerNI.getQueuedMessage(false))) {
			processCommand(parseSafe(msg));
		}
    }
    
    /**
     * Send message to debugging server for processing by message handlers.
	 * @param {String} path message handler name
	 * @param {object} JSON payload
     * 
     */
    function sendToDebugService(path, payload) {
		
        try {
			JsHybuggerNI.sendToDebugService(path, stringifySafe(payload));
        } catch (ex) {
           // console.error('JsHybugger sendToDebugService failed: ' + ex.toString());
        }
    }
    
    /**
     * Wrap browser console interface and dispatch messages to the debug server.
     */
    function replaceConsole() {

        if (!window.console) {
            window.console = {};
        }
        
        ['info', 'log', 'warn', 'error', 'debug', 'trace','group','groupEnd','assert'].forEach(function(f) {
        	localConsole[f] = window.console[f];

        	var oldFunc = window.console[f], levels = {
					log : 'log',
					warn : 'warning',
					info : 'info',
					error : 'error',
					debug : 'debug',
					trace : 'log',
					group : 'log',
					groupEnd : 'log',
					assert : 'error'
				}, types = {
					info : 'log',
					log : 'log',
					warn : 'log',
					debug : 'log',
					error : 'log',
					trace : 'trace',
					group : 'startGroup',
					groupEnd : 'endGroup',
					assert : 'assert'
				};
            
            window.console[f] = function() {
			
                var args = Array.prototype.slice.call(arguments),
                 	parameters = [], i,
					type = typeof (args[i]),
					arg = {
						type : type
					},
					prop,
					propVal,
					propType;

                /* Write to local console first */
                if (oldFunc) {
					oldFunc.apply(window.console, args);
                } 

                // special handling for assert calls
                if (f == 'assert') {
					if (args[0] === true) {
						return;
					}
					// else remove first item 
					args = args.splice(1,1);
				}
                

				for ( i = 0; args && i < args.length; i++) {
					
					if (type == 'object') {
						arg.description = arg.className = args[i].constructor &&
								args[i].constructor.name ? args[i].constructor.name :
								'Object';
						arg.objectId = "not_supported";

						arg.preview = {
							lossless : true,
							overflow : false,
							properties : []
						};

						for ( prop in args[i]) {
							propVal = args[i][prop],
							propType = typeof(propVal);

							arg.preview.properties
									.push({
										name : prop,
										type : (propType == 'object' && propVal.constructor && propVal.constructor.name ? 
												propVal.constructor.name : propType),
										value : propVal
									});
						}

					} else {
						arg.value = args[i];
					}

					parameters.push(arg);
				}

                
                sendToDebugService('Console.messageAdded', {
					message : {
						level : levels[f],
						line : lastLine,
						parameters : parameters,
						repeatCount : 1,
						source : "console-api",
						stackTrace : getStacktrace(),
						text : args && args.length > 0 ? args[0]
								: '',
						type : types[f],
						url : lastFile
					}
				});
            };
        });
    }
    
    /**
	 * @return {object} stack object created by pushStack method.
	 * @param {string}
	 *            objectId object identifier i.e. stack:0:varname
	 */
    function getStackForObjectId(objectId) {
		if (objectId) {
			var objectParams = objectId.split(":");
			return objectParams.length > 1 &&
					callStackDepth >= objectParams[1] ? callStack[objectParams[1]] :
					null;
		}
		return null;
	}

    /**
     * Debugger message processing.
	 * @param {object} cmd message from debug server
	 * @return {boolean} true for non break-able messages 
     */
    function processCommand(cmd) {

		if (cmd) {
			
			switch (cmd.command) {
			
				case 'Debugger.continueToLocation':
				case 'Debugger.resume':
				case 'Debugger.stepOver':
				case 'Debugger.stepInto':
				case 'Debugger.stepOut':
					return runSafe(cmd.command, function() {
							var fctn, rVal;
							
							fctn = eval(cmd.command);
							rVal = fctn(cmd.data ? cmd.data.params : null, cmd.replyId);
							if (rVal && cmd.replyId) {
								JsHybuggerNI.sendReplyToDebugService(cmd.replyId, stringifySafe(rVal));
							}
						}, false);
					
				case 'timeout':
					return true;

				case 'ClientConnected':
					return true;

				default:
					// default dispatching 
					// forward message for further processing to Handler implementation i.e. Page, Database.  
					try {
						var fctn = eval(cmd.command);
						if (fctn) {
							return runSafe(cmd.command, function() {
								var rVal = fctn(cmd.data.params, cmd.replyId);
								if (rVal && cmd.replyId) {
									JsHybuggerNI.sendReplyToDebugService(cmd.replyId, stringifySafe(rVal));
								}
							}, true);
						} else {
							if (cmd.replyId) {
								// silently fail - and return empty response
								JsHybuggerNI.sendReplyToDebugService(cmd.replyId, stringifySafe({ error: cmd.command}));
							}
						}
					} catch (ex) {
						console.log("dispatching error for '" + cmd.command +": " + ex);
					}
					return true;

			}
		}
    }
    

    function getStacktrace() {
		var stacktrace = [],
			i,
			stack;
	
		if (callStack) {
			for ( i = callStack.length - 1; i >= 0; i--) {
				stack = callStack[i];
				// if(stack.file.indexOf("console.") < 0) {
	
				stacktrace.push({
					columnNumber : 0,
					functionName : stack.name,
					lineNumber : i == callStack.length - 1 ? lastLine + 1
							: stack.line + 2,
					url : stack.file
				});
				// }
			}
		}
	
		return stacktrace;
	}
    
    /** 
     * Wrapper around JSON.stringify() function.
     */
	function stringifySafe(obj) {

		// first try - standard stringify - if this fails, do custom JSON
		// stringify
		try {
			return JSON.stringify(obj);
		} catch (er) {
			console.warn("JSON.stringify() failed, use fallback version. " + er);
		}

		var printedObjects = [],
			printedObjectKeys = [],
			lastKey, lastVal;

		function printOnceReplacer(key, value) {
			lastKey = key;
			lastVal = value;

			var printedObjIndex = false,
				qualifiedKey;

			printedObjects.forEach(function(obj, index) {
				if ((obj === value) && (typeof (value) == "object")) {
					printedObjIndex = index;
				}
			});

			if (printedObjIndex && typeof (value) == "object") {
				return "(see object with key " + printedObjectKeys[printedObjIndex] + ")";
			} else {
				try {
					// HTMLInputElement will be not serializable
					/*
					 * if (typeof(value)=="object") { var name =
					 * (value.constructor ? value.constructor.name :
					 * 'none'); if (name != 'Object' && name != 'Array') {
					 * console.log("stringify: " + name + ", key: " + key); } }
					 */
					if ((typeof (value) == "object") && value.constructor &&
							value.constructor.name === "HTMLInputElement") {
						return null;
					}
					qualifiedKey = key || "(empty key)";
					printedObjects.push(value);
					printedObjectKeys.push(qualifiedKey);
					return value;
				} catch (er) {
					return null;
				}
			}
		}
		return JSON.stringify(obj, printOnceReplacer);
	}
	
	/**
	 * JSON parsing with exception handling.
	 * 
	 * @param {string}
	 *            str JSON string data
	 * @return {object} JSON object or null on parsing failure
	 * 
	 */
    function parseSafe(str) {
        try {
            return JSON.parse(str);
        } catch (ex) {
            return null;
        }
    }

	/**
	 * Runs a function in an exception handled scope.
	 * @param {string} name identifier for code block
	 * @param {function} fctn function to execute
	 * @param {retVal} retVal return value on exception
	 * 
	 */
    function runSafe(name, fctn, retVal) {
		try {
			fctn();
		} catch (ex) {
			var str = "", i;
			
			for (i in ex) {
				str += i + ",";
			}
			return console.log("runSafe failed for: " + name + ", " + ex);
		} finally {
			return retVal;
		}
	}

    /**
	 * Sends "Debugger.paused" message to debug server.
	 */
    function sendDebuggerPaused(reason, auxData) {
		// clear continue to location information on pause
		continueToLocation = null;
		var callFrames = prepareStackInfo();
		sendToDebugService('Debugger.paused', {
			reason : reason,
			auxData : auxData,
			url : lastFile,
			lineNumber : lastLine,
			callFrames : callFrames
		});
		
		processMessages(true);
	}
    
    /**
	 * Prepares stack info after break has occurred.
	 */
    function prepareStackInfo() {
		var stackInfo = [],
			frameInfo,
			i,
			scope;

		for ( i = callStackDepth - 1; i >= 0; i--) {

			frameInfo = {};
			frameInfo.callFrameId = "stack:" + i;
			frameInfo.functionName = callStack[i].name;
			frameInfo.location = {
				scriptId : i == callStackDepth - 1 ? lastFile
						: callStack[i + 1].lastFile,
				lineNumber : i == callStackDepth - 1 ? lastLine
						: callStack[i + 1].lastLine,
				columnNumber : 0
			};

			// add frame 'scopeChain[]' info
			frameInfo.scopeChain = [];
			scope = {
				object : {},
				type : 'local'
			};

			scope.object = {
				type : 'object',
				objectId : 'stack:' + i,
				className : 'Object',
				description : 'Object'
			};
			frameInfo.scopeChain.push(scope);

			// add frame 'this' info
			frameInfo['this'] = {
				type : typeof (callStack[i].that),
				objectId : 'stack:' + i + ':this',
				className : callStack[i].that.constructor.name,
				description : callStack[i].that.constructor.name
			};

			stackInfo.push(frameInfo);
		}
		return stackInfo;
	}

    /**
	 * Sends 'GlobalInitHybugger' to debug server. This function is called after
	 * this script has been parsed.
	 */
	function initHybugger() {
		replaceConsole();
		sendToDebugService('GlobalInitHybugger', { frameId : FRAME_ID, url : location.href, securityOrigin : location.origin  });
	}

	/*
     * Begin: Public API functions
     */ 
    
    /**
     * Send 'GlobalPageLoaded' message to debug server message handlers.
     */
    function pageLoaded() {

    	var msg;
		sendToDebugService('GlobalPageLoaded', {
			frameId : FRAME_ID
		});

		// before returning - process all pending queue messages
		while ((msg = JsHybuggerNI.getQueuedMessage(false))) {
			processCommand(parseSafe(msg));
		}
	}

    /**
     * Used by the instrumented code to keep track of the actual processed statement.
	 * @param {string} file actual processed file
	 * @param {number} line actual processed line number within file
	 * @param {boolen} isDebuggerStatement true signals a debugger literal
     */
    function track(file, line, isDebuggerStatement) {
    	
    	var breakPointId, isBreakpoint, cond;
    	
		lastFile = file;
		lastLine = line;
		breakPointId = breakpoints[file]?breakpoints[file][line]:null;
		        
		isBreakpoint = (breakPointId) || /* breakpoint set? */
				isDebuggerStatement	|| /* break on debugger; keyword? */
				shouldBreak(callStackDepth) || /* break on next (in|over|out) */
				(continueToLocation && continueToLocation.file == file && continueToLocation.line == line);

		if (!isBreakpoint || !breakpointsActive) {
			return;
		}

		if (breakPointId && breakpointsById[breakPointId].condition) {
			cond = breakpointsById[breakPointId].condition; 
			try {
				if (!callStack[callStackDepth - 1].evalScope(cond)) {
					return;
				}
			} catch (ex) {
				console.error('invalid breakpoint condition: ' + ex);
				return;
			}
		}

		sendDebuggerPaused('other', {});
	}

	/**
	 * Used by the instrumented code to report thrown exceptions in the
	 * code.
	 */
	function reportException(e) {
			
		console.error(e.toString());
		if (pauseOnExceptionsState != 'none') {
			// none, all, uncaught - at the moment uncaught and all is the
			// same
			sendDebuggerPaused('exception', {
				description : e.toString()
			});
		}
	}
    
    /**
	 * Used by the instrumented code to track function entries.
	 */
    function pushStack(that, evalScopeFunc, functionName, vars, file, line) {
        callStack.push({depth : callStackDepth, that : that, evalScope: evalScopeFunc, name : functionName, file : file, line : line, lastFile : lastFile, lastLine : lastLine, varnames : vars });
        ++callStackDepth;
    }
    
    /**
     * Used by the instrumented code to track function exists.
     */
    function popStack() {
        callStack.pop();
        --callStackDepth;
    }
    
    /**
     * Used by the instrumented code to track javascript file loads.
     */
    function loadFile(filename, numLines) {
		sendToDebugService('Debugger.scriptParsed', {
			url : filename,
			numLines : numLines
		});

		// process messages here to make sure all breakpoints are set
		processMessages(false);
	}

	/**************************************************
	 * Begin: Protocol message handler implementations.  
	 **************************************************/
    
    /*
     * Namespace for all Debugger related messages 
     */
    Debugger = {

		/**
		 * Handles "evaluateOnCallFrame" messages and send back result to debugger client.
		 */
		evaluateOnCallFrame : function(params) {

			return Runtime.evaluateOnCallFrame(params);
		},

		/**
		 * Handles "setOverlayMessage" messages and send back result to debugger client.
		 */
		setOverlayMessage : function(params) {

			/* DOESN'T work because DOM is not updated during debugging */
			return {};
		},
		
		/**
		 * Handles "resume" messages and send back result to debugger client.
		 */
    	resume : function() {

			shouldBreak = function() { return false; };
			return { };
		},

		/**
		 * Handles "stepOver" messages and send back result to debugger client.
		 */
		stepOver : function() {
			shouldBreak = (function(oldDepth) {
				return function(depth) {
					return depth <= oldDepth;
				};
			})(callStackDepth);
			
			return { };
		},

		/**
		 * Handles "stepInto" messages and send back result to debugger client.
		 */
		stepInto : function() {

			shouldBreak = function() { return true; };
			return { };
		},

		/**
		 * Handles "stepOut" messages and send back result to debugger client.
		 */
		stepOut : function() {

			shouldBreak = (function(oldDepth) {
				return function(depth) {
					return depth < oldDepth;
				};
			})(callStackDepth);
			return { };
		},

		/**
		 * Handles "continueToLocation" messages and send back result to debugger client.
		 * @param {object} params object from debug server
		 */
		continueToLocation : function(params) {

			continueToLocation = {
					file : params.location.scriptId,
					line : params.location.lineNumber
			};

			return { };
		},

		/**
		 * Handles "setPauseOnExceptions" messages and send back result to debugger client.
		 * @param {object} params object from debug server
		 */
		setPauseOnExceptions : function(params) {
			pauseOnExceptionsState = params.state;
			return {};
		},

		/**
		 * Handles "setBreakpointsActive" messages and send back result to debugger client.
		 * @param {object} params object from debug server
		 */
		setBreakpointsActive : function(params) {

			breakpointsActive = params.active;
			return {};
		},

		/**
		 * Handles "setBreakpointByUrl" messages and send back result to debugger client.
		 * @param {object} params object from debug server
		 */
		setBreakpointByUrl : function(params) {

			var file = params.url,
				line = params.lineNumber,
				breakpointId;
			
			if (!breakpoints[file]) {
				breakpoints[file] = {};
			}
			breakpointId= file + ":" + line;
			breakpoints[file][line] = breakpointId;

			//console.log("set-breakpoint: " + ((breakpoints[file] && breakpoints[file][line]) || false ) + ", file: " + file + ", line: "+ line);
			breakpointsById[breakpointId] = params;
			return { breakpointId : breakpointId, lineNumber : line };
		}, 

		/**
		 * Handles "removeBreakpoint" messages and send back result to debugger client.
		 * @param {object} params object from debug server
		 */
		removeBreakpoint : function(params) {
		
			var data = breakpointsById[params.breakpointId];
			if (data) {
				//console.log("remove-breakpoint: " + cmd.data.breakpointId);

				delete breakpointsById[params.breakpointId];
				delete breakpoints[data.url][data.lineNumber]; 

				return { breakpointId : params.breakpointId};
			} else {
				return {};
			}
		}
    }; // end runtime namespace
    
    /*
     * Namespace for all Runtime related messages 
     */
    Runtime = {

		/**
		 * Handles "getProperties" messages and send back result to debugger client.
		 * @param {object} params object from debug server
		 */
		getProperties : function(params) {
			
			var objectParams = params.objectId.split(":"),
				results = [],
				expr,
				oType,
				oVal,
				result,
				stack = objectParams[0] === 'stack' ? callStack[objectParams[1]] : undefined,
				i,
				varnames,
				obj,
				fctnBody;
			
			if (stack && (objectParams.length == 2)) {
				varnames = stack && stack.varnames ? stack.varnames.split(",") : [];
				for (i=0; i < varnames.length; i++) {
					try {
						expr = stack.evalScope(varnames[i]);
						result = {};
						oType = typeof(expr);
						result.value = {
							type:oType,
							description: String(expr)
						};
						if (expr && (oType == 'object')) {
							result.value.objectId='stack:' + objectParams[1] + ":"+ varnames[i];
							result.value.description = expr.constructor && expr.constructor.name ? expr.constructor.name : 'object';
						} else {
							result.value.value = expr;
						}
						
						result.writable = false;
						result.enumerable = false;
						result.configurable = false;
						result.name = varnames[i];
						results.push(result);
					} catch (e) {
						console.error("getProperties() failed for variable: " + varnames[i] + ", " + e);
					}
				}
			} else {
				obj = Runtime._getObject(params.objectId);
				
				for (expr in obj) {
					
					// filter out not own properties
					if (params.ownProperties && !obj.hasOwnProperty(expr)) {
						continue;
					}
					

					oVal = obj[expr];
					oType = typeof (oVal);
					result = {};

					result.value = {
						type : oType,
						description : ""
					};
					if (oVal && (oType == 'object')) {
						result.value.objectId = params.objectId + "." + expr;
						result.value.description = oVal.constructor &&
								oVal.constructor.name ? oVal.constructor.name :
								'object';
					} else if (oType == 'function') {
						fctnBody = oVal ? oVal.toString() : 'function anonymous()';
						if (fctnBody) {
							fctnBody = fctnBody.substr(0, fctnBody.indexOf(')')+1) + "{ }";
						} 
						result.value.description = fctnBody;
					} else {
						if (oType != 'undefined') {
							result.value.value = oVal;
						}
					}

					result.writable = false;
					result.enumerable = false;
					result.configurable = false;
					result.name = String(expr);
					results.push(result);
				}
			}		

			return { result : results };
		},

		callFunctionOn : function(params) {
			
			var obj = Runtime._getObject(params.objectId),
				fctn = Function('return (' + params.functionDeclaration + ').apply(this,arguments)'),
				val = obj && fctn ? fctn.apply(obj, params.arguments) : {},
				result = {
				};
			
			if (!params.returnByValue && typeof(val) == 'object') {
				result.result = { 
						type : 'object',
						className : val.constructor ? val.constructor.name : 'Object',
						description : 'Object',
						objectId : Runtime.createObjectId(params.objectGroup, val)
				};	
				
			} else {
				result.result = { 
					type : typeof(val),
					value : val
				};	
			}
			
			return result;
		},
		
		evaluateOnCallFrame : function(params) {
			return Runtime._doEval(getStackForObjectId(params.callFrameId) || callStack[callStack.length-1], params);
		},

		evaluate : function(params) {
			return Runtime._doEval(null, params);
		}, 
		
		releaseObjectGroup : function(params) {
			var group = params.objectGroup,
				objects = objectGroups[group],
				i;
			
			if (group) {
				if (objects) {
					for (i=objects.length-1; i>=0; i--) {
						delete globalWatches[objects[i]];
					}
					delete objectGroups[group];
				}
			} else {
				objectGroups = {};
				globalWatches = {};
			}
			
			return {};
		},
		
		/**
		 * Returns the js object for a given object path.
		 * @param {string} objectId object identifier i.e. global:id
		 */
		_getObject : function(objectId) {

			var objectParams = objectId.split(":"),
				stack = objectParams[0] === 'stack' ? callStack[objectParams[1]] : undefined,
				objName = objectParams[2],
				obj = null,
				props = objName ? objName.split('.') : [],
				i;

			if (!stack) {
				props = objectParams[1].split('.');
				obj = globalWatches[props[0]];
			} else if (objName.indexOf('this') === 0) {
				obj = stack.that;
			} else if (objName.indexOf('expr') === 0) {
				obj = stack.expr;
			} else {
				obj = stack.evalScope(props[0]);
			}

			for (i=1; obj && i < props.length; i++) {
				obj = obj[props[i]];
			}
			
			return obj;
		},
		

		/**
		 * Evaluates an expression in the given scope.
		 * @param {object} evalScopeFunc scope function for resolving variables on call stack
		 * @param {object} cmd message from debug server
		 */
		_doEval : function (stack, params) {

			var response = {},
				objectGroup = params.objectGroup || 'global',
				evalResult,
				exprID;

			try {
				evalResult = stack && stack.evalScope ? stack.evalScope(params.expression) : eval.call(document, params.expression);
				if (stack) {

					response.type = typeof(evalResult);

					if (params.returnByValue) {
						response.description = response.value = evalResult;
					} else {
						exprID = "ID" + new Date().getTime();
						stack.expr = stack.expr || {};
						stack.expr[exprID] = evalResult ;

						response.objectId = "stack:" + stack.depth + ":expr." + exprID;
						if (response.type == 'object') {
							response.description = evalResult.constructor ? evalResult.constructor.name : 'object';
						} else {
							response.description = "" + evalResult;
						}
					}
				} else {
					response.type = typeof(evalResult);

					if (params.returnByValue) {
						response.description = response.value = evalResult;
					} else {

						response.objectId = Runtime.createObjectId(objectGroup, evalResult);
						if (response.type == 'object') {
							response.description = evalResult.constructor ? evalResult.constructor.name : 'object';
						} else {
							response.description = "" + evalResult;
						}
					}
				}
			} catch (ex) {
				evalResult = ex.toString();
			}  

			return { result : response };
		},
		
		/**
		 * Stores object reference in global watch groups and return identifier for the stored reference.
		 */
		createObjectId : function(objectGroup, object) {

			var exprID = "ID" + new Date().getTime();
			globalWatches[exprID] = object;
			if (!objectGroups[objectGroup]) {
				objectGroups[objectGroup] = [];
			}  
			objectGroups[objectGroup].push(exprID);
				
			return "global:" + exprID;
		}
    };  // end runtime namespace
    
    /**
     * Namespace for all Page related messages
     */
    Page = {

		/**
		 * Handles "getResourceTree" messages and send back result to debugger client.
		 * @param {object} params message from debug server
		 */
		getResourceTree : function(params, replyId) {
			JsHybuggerNI.sendReplyToDebugService(replyId, stringifySafe(Page._getResourceTree(params)));
		}, 
		
		_getResourceTree : function (params) {
			
			var i=0,
				src,
				result = {
					frameTree : {
						frame : {
							id : FRAME_ID,
							url : document.location.href.indexOf(PROTOCOL) === 0 ? document.location.href.substr(PROTOCOL.length) : document.location.href,
							loaderId: FRAME_ID,
							securityOrigin : document.location.origin,
							mimeType : 'text/html'
						},
						resources : []
					}
				},
				scripts;
			
			// get all script resources
			scripts = document.getElementsByTagName("script"); 
			for (i = 0; i < scripts.length; i++) {
				src = scripts[i].src;
				if (src.indexOf('jshybugger.js')>=0) {
					continue;
				}
				result.frameTree.resources.push({
					// remove content provider
					url : src.indexOf(PROTOCOL) === 0 ? src.substr(PROTOCOL.length) : src,
					type : 'Script',
					mimeType : 'text/x-js'
				});
			}
			
			return result;
		},
		
		/**
		 * Handles "pageReload" messages and send back result to debugger client.
		 * @param {object} params message from debug server
		 */
		pageReload : function(params) {
			shouldBreak = function() { return false; };
			breakpoints = {};
		
			setTimeout(function() {
				location.reload();
			}, 500);
			
			return {};
		}
		
    };  // end Page namespace


    // END: Message handler implemenations
    
    // register on load event handler
    window.addEventListener("load", pageLoaded, false);
    
    // now send the GlobalInitHybugger message
	initHybugger();

	// Return the public API for JsHybugger
	return {
		loadFile : loadFile,
		pushStack : pushStack,
		popStack : popStack,
		reportException : reportException,
		track : track,
		processMessages : processMessages,
	};

})();
}
