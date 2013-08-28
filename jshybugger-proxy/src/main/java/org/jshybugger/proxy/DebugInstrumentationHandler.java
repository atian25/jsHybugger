package org.jshybugger.proxy;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jshybugger.DebugContentProvider;
import org.jshybugger.server.Md5Checksum;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

public class DebugInstrumentationHandler extends SimpleChannelHandler {

	private static final String JS_HYBUGGER = "jsHybugger-";
	private final String PROVIDER_PROTOCOL;
	private static int requestId = 0;
	private String requestURI;
	private String requestMethod;
	private String sourceSelection = null;
	private ContentResolver contentProvider; 
	
	public DebugInstrumentationHandler(Context context) {
		this.contentProvider = context.getContentResolver();
		PROVIDER_PROTOCOL = DebugContentProvider.getProviderProtocol(context);
	}

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
			throws Exception {

		if (!(e.getMessage() instanceof HttpMessage)) {
			ctx.sendUpstream(e);
			return;
		}

		HttpRequest msg = (HttpRequest) e.getMessage();
		requestURI = "http://localhost:8080" + msg.getUri();
		requestMethod = msg.getMethod().getName();
		sourceSelection = msg.getHeader(DebugContentProvider.X_JS_HYBUGGER_GET);
		
		String if_header = msg.getHeader("If-None-Match");
		if (if_header != null && if_header.startsWith(JS_HYBUGGER)) {
			msg.setHeader("If-None-Match", if_header.substring(JS_HYBUGGER.length()));
			
			Cursor cur = contentProvider.query(Uri.parse(PROVIDER_PROTOCOL + requestURI),  new String[] {DebugContentProvider.IS_CACHED_SELECTION}, null, null,  null);
			if (cur != null) {
				if (!cur.moveToFirst()) {
					msg.removeHeader("Cache-Control"); 
					msg.removeHeader("If-None-Match"); 
					msg.removeHeader("If-Modified-Since");
				}
				cur.close();
			}
			
		} else if (if_header != null) {
			msg.removeHeader("Cache-Control"); 
			msg.removeHeader("If-None-Match"); 
			msg.removeHeader("If-Modified-Since");
		}

		ctx.sendUpstream(e);
	}

	@Override
	public void writeRequested(ChannelHandlerContext ctx, MessageEvent e)
			throws Exception {

		int reqCtx = (++requestId);
		
		Object msg = e.getMessage();
		if (msg instanceof HttpResponse) {
			LogActivity.addMessage(String.format("R%05d %s %s, SC=%s", reqCtx, requestMethod, requestURI, ((HttpResponse) msg).getStatus().getCode()));
		}
		
		if (msg instanceof HttpResponse
				&& ((HttpResponse) msg).getStatus().getCode() == 100) {
			
			// 100-continue response must be passed through.
			ctx.sendDownstream(e);
		} else if (msg instanceof HttpResponse) {
			HttpResponse m = (HttpResponse) msg;
			String contentType = m.getHeader("Content-Type");
			
			if ((requestURI.endsWith(".html") && !requestURI.endsWith(".jsp")) ||
					((contentType != null) && contentType.contains("html"))) {
				
				if (HttpResponseStatus.OK.equals(m.getStatus())) {
					LogActivity.addMessage(String.format("R%05d Injecting jsHybugger script", reqCtx));				
					String scriptTag = "<script src=\"http://localhost:8889/jshybugger/jshybugger.js\"></script>";
					int contentLength = m.getContent().readableBytes() + scriptTag.length();
					ChannelBuffer buffer = ChannelBuffers.buffer(contentLength);
					buffer.writeBytes(scriptTag.getBytes());
					buffer.writeBytes(m.getContent());
					
					m.setContent(buffer);
					m.setHeader("Cache-Control", "no-cache, must-revalidate");
					m.setHeader(
			                HttpHeaders.Names.CONTENT_LENGTH,
			                Integer.toString(contentLength));
				}
				
			} else if (!DebugContentProvider.ORIGNAL_SELECTION.equals(sourceSelection) &&
					   (requestURI.endsWith(".js") || ((contentType != null) && contentType.contains("javascript")))
					   && !requestURI.endsWith(".min.js")) {

				if (contentType == null) {
					m.addHeader("Content-Type", "application/javascript");
				}
				
				m.setHeader("ETag", JS_HYBUGGER + m.getHeader("ETag"));
				m.setHeader("Cache-Control", "must-revalidate");
				
				if (m.getStatus().getCode() == 304)  { // not-modified
					m.setStatus(HttpResponseStatus.OK);
					sendResourceFile(m, Uri.parse(PROVIDER_PROTOCOL + requestURI));
		            ctx.sendDownstream(e);

		            return;
				}
				
				BufferedInputStream resStream = new BufferedInputStream(new ChannelBufferInputStream(m.getContent()));
				try {
				
					ByteArrayOutputStream bOut = new ByteArrayOutputStream();
					byte[] readBuffer = new byte[4096];
					int len=0;
					while ((len = resStream.read(readBuffer))>0) {
						bOut.write(readBuffer, 0, len);
					}
					
					ContentValues values = new ContentValues();
					values.put("scriptSource", bOut.toString());
					bOut.close();
					
					Uri uri = Uri.parse(PROVIDER_PROTOCOL + requestURI);
					
					LogActivity.addMessage(String.format("R%05d Starting script instrumentation", reqCtx));				
					uri = contentProvider.insert(uri, values);
					LogActivity.addMessage(String.format("R%05d Script instrumentation successfully completed", reqCtx));
					sendResourceFile(m, uri);

				} catch (Exception ex) {

					LogActivity.addMessage(String.format("R%05d Script instrumentation failed: %s", reqCtx, ex.toString()));				
					sendResourceFile(m, Uri.parse(PROVIDER_PROTOCOL + requestURI));

				} finally {
					resStream.close();
				}
			}
			
            ctx.sendDownstream(e);
		}
	}

	private void sendResourceFile(HttpMessage m, Uri uri) 
			throws FileNotFoundException, IOException {

		Cursor cursor = contentProvider.query(uri, new String[] { "scriptSource" }, 
				null, 
				null, 
				null);
		
		String resourceContent=null;
		if (cursor != null) {
			if(cursor.moveToFirst()) {
				resourceContent = cursor.getString(0);
			}
			cursor.close();
		} else {
			resourceContent="";
		}
		
		int contentLength = resourceContent.length();
		ChannelBuffer buffer = ChannelBuffers.buffer(contentLength);
		buffer.writeBytes(resourceContent.getBytes());
		m.setContent(buffer);
		m.setHeader(
                HttpHeaders.Names.CONTENT_LENGTH,
                Integer.toString(contentLength));		
	}
	
	/**
	 * Gets the instrumented file name.
	 *
	 * @param url the url to instrument
	 * @param resource resource input stream
	 * @return the instrument file name
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public static String getInstrumentedFileName(String url, BufferedInputStream resource) throws IOException {
		String loadUrl = null;
		try {
			loadUrl = url.replaceAll("[/:?=]", "_");
					
			if (resource != null) {
				resource.mark(1000000);
				loadUrl	+= Md5Checksum.getMD5Checksum(resource);
			}
			
		} catch (Exception e) {
			throw new IOException("getInstrumentedFileName failed:" + url, e);
		} 
		return loadUrl;
	}		
}
