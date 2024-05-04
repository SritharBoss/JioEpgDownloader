package com.main;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.text.StringEscapeUtils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainClass {

	private static final String CHANNEL_URL = "https://jiotv.data.cdn.jio.com/apis/v3.0/getMobileChannelList/get/?os=android&devicetype=phone&usertype=tvYR7NSNn7rymo3F";
	private static final String EPG_URL = "https://jiotv.data.cdn.jio.com/apis/v1.3/getepg/get/?offset=%d&channel_id=%d";

	private static final OkHttpClient client = new OkHttpClient();
	private static final Gson gson = new Gson();
	private static final String EPG_XML = "epg.xml";
	private static String EPG_FILE = "epg.xml.gz";
	private static int threadCount = 10;

	public static void main(String[] args) {
		if (args.length >= 1) {
			try {
				threadCount = Integer.valueOf(args[0]);
			} catch (NumberFormatException e) {
				threadCount = 5;
			}
		}
		init();
	}

	public static void init() {
		boolean flag = false;
		File file = new File(EPG_FILE);
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
		String todayDate = sdf.format(new Date());
		System.out.println("---PROCESS STARTED---");
		System.out.println("TODAY :: " + todayDate);
		if (file.exists()) {
			// If file was modified today, don't generate new EPG
			// Else generate new EPG
			long lastModTime = file.lastModified();
			String fileDate = sdf.format(new Date(lastModTime));
			if (fileDate.equals(todayDate)) {
				System.out.println("Old EPG Generated today. No need to refresh.");
			} else {
				System.out.println("EPG file is old. Process started...");
				flag = true;
			}
		} else {
			System.out.println("EPG file doesn't exist. Process started...");
			flag = true;
		}

		if (flag) {
			genXMLGz(EPG_FILE);
		}
		System.out.println("---PROCESS COMPLETED---");
		System.exit(0);
	}

	public static void genXMLGz(String filename) {
		System.out.println("Generating XML..");
		deleteFile(EPG_XML);
		deleteFile(EPG_FILE);
		long start = System.currentTimeMillis();
		try {
			genXML();
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("XML Generation Error.");
		}
		System.out.println("XML Generated in " + ((System.currentTimeMillis() - start) / 1000) + "s");
		gzipFile(EPG_XML, EPG_FILE);
		deleteFile(EPG_XML);
	}

	public static void gzipFile(String sourceFilePath, String destFilePath) {
		try (FileInputStream fis = new FileInputStream(sourceFilePath);
				FileOutputStream fos = new FileOutputStream(destFilePath);
				GZIPOutputStream gzipOS = new GZIPOutputStream(fos)) {

			byte[] buffer = new byte[1024];
			int len;
			while ((len = fis.read(buffer)) != -1) {
				gzipOS.write(buffer, 0, len);
			}

			System.out.println("File has been gzipped successfully.");

		} catch (IOException e) {
			System.err.println("An error occurred while gzipping the file: " + e.getMessage());
		}
	}

	public static void genXML() throws IOException {
		// Fetch channels data
		Request request = new Request.Builder().url(CHANNEL_URL).build();
		Response response = client.newCall(request).execute();
		if (!response.isSuccessful()) {
			if (response.code() == 404) {
				return;
			}
			throw new IOException("Unexpected code " + response);
		}
		writeToFile(EPG_XML, "<tv version=\"\" encoding=\"\">");

		Type channelsListType = new TypeToken<ArrayList<Channel>>() {
		}.getType();
		String str = response.body().string();
		JsonObject json = gson.fromJson(str, JsonObject.class);
		List<Channel> channels = gson.fromJson(json.get("result").getAsJsonArray(), channelsListType);

		writeChannelsToFile(EPG_XML, channels);
		System.out.println("STEP 1 :: Channels Written..");
		int channelSize = channels.size();
		// Use a fixed thread pool to fetch EPG data concurrently
		ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threadCount);
		for (Channel channel : channels) {
			executor.execute(() -> fetchEPG(channel));
			// pb.stepTo(executor.getCompletedTaskCount());
			if (executor.getCompletedTaskCount() % 10 == 0) {
				System.out.print("\rProcessed :: " + executor.getCompletedTaskCount() + "/" + channelSize);
			}
			while (executor.getTaskCount() > ((threadCount * 2) + executor.getCompletedTaskCount())) {
				// waiting
			}
		}
		executor.shutdown();
		try {
			System.out.print("\rProcessed :: " + executor.getCompletedTaskCount() + "/" + channelSize);
			executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}
		System.out.print("\rProcessed :: " + executor.getCompletedTaskCount() + "/" + channelSize);
		System.out.println();
		System.out.println("STEP 2 :: Programmes Written..");
		writeToFile(EPG_XML, "</tv>");

	}

	public static void fetchEPG(Channel channel) {
		// Fetch EPG data for a given channel
		for (int offset = -1; offset < 2; offset++) {
			Request request = new Request.Builder().url(String.format(EPG_URL, offset, channel.getChannel_id()))
					.header("User-Agent", "okhttp/4.2.2").build();

			try {
				Response response = client.newCall(request).execute();
				if (!response.isSuccessful()) {
					if (response.code() == 404) {
						return;
					}
					throw new IOException("Unexpected code " + response);
				}
				String str = response.body().string();
				EPGResponse epgResponse = gson.fromJson(str, EPGResponse.class);
				writeProgramsToFile(EPG_XML, epgResponse.getEpg());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public synchronized static void writeToFile(String filename, String content) {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
			writer.write(content);
			writer.flush();
		} catch (IOException e) {
			e.printStackTrace();
			System.err.println("An error occurred while writing to the file: " + e.getMessage());
		}
	}

	public static synchronized void writeChannelsToFile(String filename, List<Channel> content) {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
			for (Channel str : content) {
				writer.write(
						"<channel id=\"" + StringEscapeUtils.escapeXml11(String.valueOf(str.getChannel_id())) + "\">");
				writer.write(
						"<display-name>" + StringEscapeUtils.escapeXml11(str.getChannel_name()) + "</display-name>");
				writer.write("</channel>");
			}
			writer.flush();
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("An error occurred while writing to the file: " + e.getMessage());
		}
	}

	public static synchronized void writeChannelToFile(String filename, Channel content) {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
			writer.write(
					"<channel id=\"" + StringEscapeUtils.escapeXml11(String.valueOf(content.getChannel_id())) + "\">");
			writer.write(
					"<display-name>" + StringEscapeUtils.escapeXml11(content.getChannel_name()) + "</display-name>");
			writer.write("</channel>");
			writer.flush();
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("An error occurred while writing to the file: " + e.getMessage());
		}
	}

	private static synchronized void writeProgramsToFile(String filename, List<Programme> content) {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
			for (Programme str : content) {
				writer.write("<programme channel=\"" + str.getChannel_id() + "\" start=\"" + str.getStartEpoch()
						+ "\" stop=\"" + str.getEndEpoch() + "\">");
				writer.write("<title lang=\"en\">" + StringEscapeUtils.escapeXml11(str.getShowname()) + "</title>");
				writer.write("<desc lang=\"en\">" + StringEscapeUtils.escapeXml11(str.getDescription()) + "</desc>");
				writer.write("<icon src=\"" + str.getEpisodePoster() + "\"/></programme>");
			}
			writer.flush();
			writer.close();
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("An error occurred while writing to the file: " + e.getMessage());
		}
	}

	public static synchronized void writeProgramToFile(String filename, Programme content) {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
			writer.write("<programme channel=\"" + content.getChannel_id() + "\" start=\"" + content.getStartEpoch()
					+ "\" stop=\"" + content.getEndEpoch() + "\">");
			writer.write("<title lang=\"en\">" + StringEscapeUtils.escapeXml11(content.getShowname()) + "</title>");
			writer.write("<desc lang=\"en\">" + StringEscapeUtils.escapeXml11(content.getDescription()) + "</desc>");
			writer.write("<icon src=\"" + content.getEpisodePoster() + "\"/></programme>");
			writer.flush();
			writer.close();
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("An error occurred while writing to the file: " + e.getMessage());
		}
	}

	public static void deleteFile(String filePath) {
		File file = new File(filePath);

		if (file.exists()) {
			if (file.delete()) {
				System.out.println("File Deleted :: "+filePath);
			}else {
				System.err.println("Failed to delete the file: " + filePath);
			}
		}
	}
}
