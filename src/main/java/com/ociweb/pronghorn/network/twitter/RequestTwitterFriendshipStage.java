package com.ociweb.pronghorn.network.twitter;

import java.util.ArrayList;
import java.util.List;

import com.ociweb.pronghorn.network.OAuth1HeaderBuilder;
import com.ociweb.pronghorn.network.ServerCoordinator;
import com.ociweb.pronghorn.network.http.HTTPUtil;
import com.ociweb.pronghorn.network.schema.ClientHTTPRequestSchema;
import com.ociweb.pronghorn.network.schema.HTTPRequestSchema;
import com.ociweb.pronghorn.network.schema.NetResponseSchema;
import com.ociweb.pronghorn.network.schema.ServerResponseSchema;
import com.ociweb.pronghorn.pipe.DataInputBlobReader;
import com.ociweb.pronghorn.pipe.DataOutputBlobWriter;
import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.stage.PronghornStage;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;

public class RequestTwitterFriendshipStage extends PronghornStage {

	private final String ck;
	private final String cs;
	private final String token;
	private final String secret;
	private OAuth1HeaderBuilder myAuth;
	
	private static final int port = 443;
	private static final String host = "api.twitter.com";
	
	//TODO: should be easy to make send tweet stage after this.
	
	private static final String followPath   = "/1.1/friendships/create.json";
	private static final String unfollowPath = "/1.1/friendships/destroy.json";
	
	private final String path;
	private final byte[] pathBytes;
	private final byte[][] postItems;
	private final int msBetweenCalls;
	
	private final Pipe<HTTPRequestSchema>[] inputs;
	private final Pipe<ServerResponseSchema>[] outputs;	
	private final Pipe<ClientHTTPRequestSchema>[] toTwitter;
	private final Pipe<NetResponseSchema>[] fromTwitter;
	
	private long nextTriggerTime = -1;
	private String hostAndPath;
	
	private final int contentPosition = 0;
	private final int contentMask = Integer.MAX_VALUE;
	private byte[] contentBacking;
	
	/////////////
	//POST https://api.twitter.com/1.1/friendships/create.json?user_id=1401881&follow=true
    //POST https://api.twitter.com/1.1/friendships/destroy.json?user_id=1401881
	/////////////		
	
	public static RequestTwitterFriendshipStage newFollowInstance(GraphManager graphManager,
			String ck, String cs, 
			String token, String secret, 
			Pipe<HTTPRequestSchema>[] inputs, Pipe<ServerResponseSchema>[] outputs,
			Pipe<ClientHTTPRequestSchema>[] toTwitter, Pipe<NetResponseSchema>[] fromTwitter) {
		return new RequestTwitterFriendshipStage(graphManager, ck, cs, token, secret, 
				inputs, outputs, toTwitter, fromTwitter, followPath, "follow=true".getBytes());			 
	}
	
	public static RequestTwitterFriendshipStage newUnFollowInstance(GraphManager graphManager,
			String ck, String cs, 
			String token, String secret, 
			Pipe<HTTPRequestSchema>[] inputs, Pipe<ServerResponseSchema>[] outputs,
			Pipe<ClientHTTPRequestSchema>[] toTwitter, Pipe<NetResponseSchema>[] fromTwitter) {
		return new RequestTwitterFriendshipStage(graphManager, ck, cs, token, secret, 
				inputs, outputs, toTwitter, fromTwitter, unfollowPath);			 
	}
	
	private RequestTwitterFriendshipStage(
			GraphManager graphManager,
			String ck, String cs, 
			String token, String secret, 
			Pipe<HTTPRequestSchema>[] inputs, 
			Pipe<ServerResponseSchema>[] outputs,
			Pipe<ClientHTTPRequestSchema>[] toTwitter, 
			Pipe<NetResponseSchema>[] fromTwitter,
			String path,
			byte[] ... postItems
			) {
		
		super(graphManager, inputs, join(outputs, toTwitter));
		
		this.inputs = inputs;
		this.outputs = outputs;
		this.toTwitter = toTwitter;
		this.fromTwitter = fromTwitter;
		
		this.ck = ck;
	    this.cs = cs;
		this.token = token;
		this.secret = secret;
			
		this.path = path;
		this.pathBytes = path.getBytes();
		this.postItems = postItems;
		
		assert(inputs.length == outputs.length);
		assert(outputs.length == toTwitter.length);
		assert(toTwitter.length == fromTwitter.length);		
		
		this.msBetweenCalls = (24*60*60*1000)/1000;  //(15*60*1000)/15; 15 min limit
		assert(86_400 == msBetweenCalls);  //24 hour limit of 1000
				
	}
	
	@Override
	public void startup() {
		contentBacking = new byte[4];
		
		myAuth = new OAuth1HeaderBuilder(ck, cs, token, secret);
		hostAndPath = myAuth.buildFormalPath(port, "https", host, path);
	}
	
	@Override
	public void run() {
		
		int i = inputs.length;
		while (--i>=0) {
			run(i, inputs[i], outputs[i], toTwitter[i], fromTwitter[i]);
		}

	}

	private void run(int idx, 
			         Pipe<HTTPRequestSchema> input,
			         Pipe<ServerResponseSchema> output,
			         Pipe<ClientHTTPRequestSchema> toTwitter,
			         Pipe<NetResponseSchema> fromTwitter) {
				
		
		while (Pipe.hasContentToRead(fromTwitter) 
			   && Pipe.hasRoomForWrite(output)) {
			
			long connectionId = -1;
			int sequenceID = -1;
									
			
			int msgIdx = Pipe.takeMsgIdx(fromTwitter);
			if (NetResponseSchema.MSG_RESPONSE_101==msgIdx) {
				long connId = Pipe.takeLong(fromTwitter);
				int flags = Pipe.takeInt(fromTwitter);
				DataInputBlobReader<NetResponseSchema> payload = Pipe.openInputStream(fromTwitter);
				
				final short statusId = payload.readShort();
				
				int contentLength;
				if (200==statusId) {
					contentLength = 0;
				} else {
					contentLength = 1;
					contentBacking[0] = (byte)127;
				}
				
				int channelIdHigh = (int)(connectionId>>32); 
				int channelIdLow = (int)connectionId;
				
				HTTPUtil.simplePublish(ServerCoordinator.END_RESPONSE_MASK | ServerCoordinator.CLOSE_CONNECTION_MASK, 
						      sequenceID, statusId, output, channelIdHigh, channelIdLow, null,
						      contentLength, contentBacking, contentPosition, contentMask);
            	 
				
				
			} else {
				
				Pipe.skipNextFragment(fromTwitter, msgIdx);
				
				
			}
			
			
			
			HTTPUtil.publishStatus(connectionId, sequenceID, 200, output);
			
		}
		
		
		while (Pipe.hasContentToRead(input) 
				&& Pipe.hasRoomForWrite(output)
				&& Pipe.hasRoomForWrite(toTwitter)) {
			
			long now = System.currentTimeMillis();
			
			if (isThisTheTime(now)) {
				//do it now, toTwitter then take response and relay it back
				publishRequest(toTwitter, idx);
				
			} else {
				//too quickly, send back 420
				long connectionId = -1;
				int sequenceID = -1;
								
				
				int contentLength = 1;
				long seconds = ( (nextTriggerTime-now)+1000 )/1000;
				if (seconds>127) { //cap out at 127 seconds
					seconds = 127;
				}
				contentBacking[0] = (byte)seconds;
				
				
				int channelIdHigh = (int)(connectionId>>32); 
				int channelIdLow = (int)connectionId;
				
				HTTPUtil.simplePublish(ServerCoordinator.END_RESPONSE_MASK | ServerCoordinator.CLOSE_CONNECTION_MASK, 
						      sequenceID, 420, output, channelIdHigh, channelIdLow, null,
						      contentLength, contentBacking, contentPosition, contentMask);
	
			}			
		}
	}

	private boolean isThisTheTime(long now) {
		if (now > nextTriggerTime) {
			nextTriggerTime = now + msBetweenCalls;
			return true;
		} else {
			return false;
		}
	}
	
	private void publishRequest(Pipe<ClientHTTPRequestSchema> pipe, int httpRequestResponseId) {
		
		int size = Pipe.addMsgIdx(pipe, ClientHTTPRequestSchema.MSG_HTTPGET_100);
		assert(httpRequestResponseId>=0);
		
		Pipe.addIntValue(httpRequestResponseId, pipe);//destination
		Pipe.addIntValue(httpRequestResponseId, pipe);//session
		Pipe.addIntValue(port, pipe);                 //port
		Pipe.addUTF8(host, pipe);
		Pipe.addUTF8(path, pipe);
				
		DataOutputBlobWriter<ClientHTTPRequestSchema> stream = Pipe.openOutputStream(pipe);
		writeHeaders(stream);
		DataOutputBlobWriter.closeLowLevelField(stream);

		Pipe.confirmLowLevelWrite(pipe, size);
		Pipe.publishWrites(pipe);
		
	}

	private void writeHeaders(DataOutputBlobWriter<ClientHTTPRequestSchema> stream) {
		
		//TODO: we have a lot here to improve and eliminate GC.
		
		List<CharSequence[]> javaParams = new ArrayList<CharSequence[]>(2);		
		
		if (path==followPath) {
			javaParams.add(new CharSequence[]{"follow","true"});
		}
		
		myAuth.addHeaders(stream, javaParams, "GET", hostAndPath);
		stream.append("\r\n");
	}

}