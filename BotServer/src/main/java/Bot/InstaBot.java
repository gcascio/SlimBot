package Bot;

import WebPageHandler.*;
import WebPageHandler.InstaJsonManager.*;
import MyUtilities.*;
import RandomTools.*;
import TimeUtil.*;

import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

import java.io.IOException;
import java.io.OutputStream;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;

import com.google.gson.Gson;

public class InstaBot extends Thread{

	private AtomicBoolean pauseFlag = new AtomicBoolean(false);
	private AtomicBoolean stopFlag = new AtomicBoolean(false);
	private AtomicBoolean statFlag = new AtomicBoolean(false);
	private AtomicBoolean runningFlag = new AtomicBoolean(false);

	private boolean unfollow = true;
	private int lowerUnfollowNr = 0;
	private int upperUnfollowNr = 1;
	private int runTime = (int) (60 * 1 );
	private boolean infRun = false;
	private final String username;
	private final String password;
	private int maxFollow = 100;
	private int maxLike = 300;

	private SimpleNode currentNode;
	private List<String> tagList;
	private int followed = 0;
	private int liked = 0;

	InstaHandler instaHandler;
	RandomRingBuffer<String> randomTagBuffer;
	RandomDecision randomDecision;
	TimeManager timeManager;
	BotStats stats;


	public InstaBot(String user, String pass, List<String> tags){

		username = user;
		password = pass;
		tagList = tags;
		this.setName(username);

		instaHandler = new InstaHandler();
		randomTagBuffer = new RandomRingBuffer<String>(tags);
		randomDecision = new RandomDecision(100);
		timeManager = new TimeManager();
		stats = new BotStats();

		instaHandler.initialize(username);
	}

	public InstaBot(InstaBot bot){
		this(bot.getUsername(), bot.getPassword(), bot.getTagList());
	}

	@Override
		public void run(){
			runningFlag.set(true);

			instaHandler.login(username, password);
			randomDecision.randomWait(1000, 2000);

			stats.setSessionTime(runTime);

			InputServer inputServer = new InputServer(this);
			inputServer.start();

			if(unfollow){
				//unfollow certain number of followers
				int unfollowed = instaHandler.unfollowChunkProtected(username, 10);
				stats.setUnfollowedInSession(unfollowed);
			}

			while(infRun || (timeManager.checkTime(runTime) && !(followed >= maxFollow && liked >= maxLike) && !stopFlag.get())){

				while(pauseFlag.get()){System.out.println("pause while");};

				InstaHandler.TagSearchHandler tagHandler = instaHandler.new TagSearchHandler(randomTagBuffer.next());
				System.out.println("1st while");
				while( (currentNode = tagHandler.nextNewPic() ) != null && timeManager.checkTime(runTime) && followed <= maxFollow && liked <= maxLike && !stopFlag.get()){

					while(pauseFlag.get()){System.out.println("pause while");};
					System.out.println("2nd while");

					System.out.println("-----------> next new pic");

					randomDecision.randomWait(300, 2000);

					if(currentNode.containsHashtag(tagList) && randomDecision.rand(30)){

						if(randomDecision.rand(2) && followed <= maxFollow){
							instaHandler.follow(currentNode.getOwner());
							stats.inkrementFollowedInSession();
						} else if (randomDecision.rand(6) && liked <= maxLike){
							instaHandler.like(currentNode.getId());
							stats.inkrementLikedInSession();
						}


					}
				}

			}

			stats.setFollowedBy(Integer.parseInt(instaHandler.getFollowedByCount(username)));
			stats.setFollowing(Integer.parseInt(instaHandler.getFollowingCount(username)));

			instaHandler.logout(username);
			waitForStatsRequest(300);//wait 5min for stat request
			inputServer.stop();
			runningFlag.set(false);
			System.out.println("out ===> ");
		}

	
		public void stopBot(){
			setStop(true);
		}

		public String getUsername(){
			return username;
		}

		public String getPassword(){
			return password;
		}

		public List<String> getTagList(){
			return tagList;
		}

		public BotStats getBotStats(){
			return stats;
		}

		public boolean getPause(){
			return pauseFlag.get();
		}

		public boolean getStop(){
			return stopFlag.get();
		}

		public boolean getStat(){
			return statFlag.get();
		}

		public boolean botIsRunning(){
			System.out.println("in botIsRunning");
			return runningFlag.get();
		}

		public boolean getUnfollow(){
			return unfollow;
		}

		public boolean getInfRun(){
			return infRun;
		}

		public int getRunTime(){
			return runTime;
		}

		public int getMaxFollow(){
			return maxFollow;
		}	

		public int getMaxLike(){
			return maxLike;
		}

		public void setMaxFollow(int max){
			maxFollow = max;
		}

		public void setMaxLike(int max){
			maxLike = max;
		}

		public void setRunTime(int time){
			runTime = time;
		}

		public void setInfRun(boolean state){
			infRun = state;
		}
		public void setUnfollow(boolean state){
			unfollow = state;
		}

		public void setPause(boolean state){
			pauseFlag.set(state);
		}

		public void setStop(boolean state){
			stopFlag.set(state);
		}

		public void setStat(boolean state){
			statFlag.set(state);
		}

		private void waitForStatsRequest(int time){
			TimeManager timeMan = new TimeManager();
			System.out.println("waiting for stats request; time = "+ timeMan.checkTime(time)+"statFlag = "+ getStat());
			
			while(!getStat() && timeMan.checkTime(time) && !stopFlag.get());
			setStat(false);
		}

}

class InputServer{
	HttpServer server;
	private final String response = "\nOk";
	private InstaBot bot;

	InputServer(InstaBot bot){
		try{
			this.bot = bot;
			server = HttpServer.create(new InetSocketAddress(8000), 0);
			server.createContext("/stop", new stopHandler());
			server.createContext("/pause", new pauseHandler());
			server.createContext("/stats", new statResponse());
			server.createContext("/endstats", new endStatResponse());
			server.setExecutor(null); // creates a default executor
		} catch(Exception e){
			e.printStackTrace();
		}
	}

	public void start(){
		server.start();
	}

	public void stop(){
		server.stop(1);
	}

	class stopHandler implements HttpHandler {

		public void handle(HttpExchange t) throws IOException {
			bot.setStop(true);
			t.sendResponseHeaders(200, response.length());
			OutputStream os = t.getResponseBody();
			os.write(response.getBytes());
			os.close();
			System.out.println("###########\nSystem Stoped!\n##########");
		}
	}

	class pauseHandler implements HttpHandler {

		public void handle(HttpExchange t) throws IOException {
			System.out.println("in        ###########################");
			if(bot.getPause()){ 
				bot.setPause(false);
				System.out.println("###########\nSystem Continued!\n##########");
			} else {
				bot.setPause(true);
				System.out.println("###########\nSystem Paused!\n##########");
			}
			t.sendResponseHeaders(200, response.length());
			OutputStream os = t.getResponseBody();
			os.write(response.getBytes());
			os.close();
		}
	}

	class statResponse implements HttpHandler {
	
		public void handle(HttpExchange t) throws IOException {
			Gson gson = new Gson();
			String json = gson.toJson(bot.getBotStats());
			t.sendResponseHeaders(200, json.length());
                        OutputStream os = t.getResponseBody();
                        os.write(json.getBytes());
                        os.close();
                        System.out.println("###########\nJSON sendt!\n##########");
		}

	}

	class endStatResponse implements HttpHandler {

                public void handle(HttpExchange t) throws IOException {
                        Gson gson = new Gson();
                        String json = gson.toJson(bot.getBotStats());
                        t.sendResponseHeaders(200, json.length());
                        OutputStream os = t.getResponseBody();
                        os.write(json.getBytes());
                        os.close();
                        bot.setStat(true);
                        System.out.println("###########\nEnd JSON sendt!\n##########");
                }

        }

}

class BotStats{
	int followedBy;
	int following;
	int followedInSession;
	int likedInSession;
	int unfollowedInSession;
	int commentedInSession;
	int sessionTime;

	BotStats(){
		followedBy = 0;
	        following = 0;
		followedInSession = 0;
        	likedInSession = 0;
        	unfollowedInSession = 0;
        	commentedInSession = 0;
        	sessionTime = 0;
	}

	public void inkrementFollowedInSession(){
		followedInSession++;
	}

	public void inkrementLikedInSession(){
		likedInSession++;
	}

	public void setUnfollowedInSession(int val){
		unfollowedInSession = val;
	}

	public void setFollowedBy(int val){
		followedBy = val;
	}

	public void setFollowing(int val){
		following = val;
	}

	public void setSessionTime(int time){
		sessionTime = time;
	}

}
