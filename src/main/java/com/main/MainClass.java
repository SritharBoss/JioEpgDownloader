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
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
	private static List<Programme> list = new ArrayList<Programme>();
	private static final String TEMP = "temp";
	private static String EPG_FILE = "epg.xml.gz";
	private static int threadCount=5;

	public static void main(String[] args) {
		if(args.length>=1) {
			try {
				threadCount=Integer.valueOf(args[0]);
			} catch (NumberFormatException e) {
				threadCount=3;
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
		System.out.println("TODAY :: "+todayDate);
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

		Runnable genepg = () -> {
			try {
				genXMLGz(EPG_FILE);
			} catch (IOException e) {
				e.printStackTrace();
			}
		};

		if (flag) {
			genepg.run();
		}
		System.out.println("---PROCESS STARTED---");
		System.exit(0);
	}

	public static void genXMLGz(String filename) throws IOException {
		System.out.println("Generating XML..");
		deleteFile(TEMP);
		deleteFile(EPG_FILE);
		long start=System.currentTimeMillis();
		genXML();
		System.out.println("XML Generated in "+((System.currentTimeMillis()-start)/1000)+"s");
		gzipFile(TEMP, EPG_FILE);
		deleteFile(TEMP);
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

		Type channelsListType = new TypeToken<ArrayList<Channel>>() {
		}.getType();
		String str = response.body().string();
		JsonObject json = gson.fromJson(str, JsonObject.class);
		List<Channel> channels = gson.fromJson(json.get("result").getAsJsonArray(), channelsListType);

		// Use a fixed thread pool to fetch EPG data concurrently
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);

		for (Channel channel : channels) {
			executor.execute(() -> fetchEPG(channel));
		}

		executor.shutdown();
		try {
			executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}

		writeToFile(TEMP, "<tv version=\"\" encoding=\"\">");
		channels = channels.stream()
				.sorted(Comparator.comparingInt(c -> c.getChannelLanguageId() == 8 ? 0 : c.getChannel_id()))
				.collect(Collectors.toList());
		writeChannelToFile(TEMP, channels);
		// list.stream().sorted(Comparator.comparing(Programme::getChannel_id)).collect(Collectors.toList());
		writeProgramToFile(TEMP, list);
		writeToFile(TEMP, "</tv>");

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
				// Process the response as needed
				addToList(epgResponse.getEpg());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private synchronized static void addToList(List<Programme> l) {
		list.addAll(l);
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

	public synchronized static void writeChannelToFile(String filename, List<Channel> content) {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
			for (Channel str : content) {
				writer.write("<channel id=\"" + StringEscapeUtils.escapeXml11(String.valueOf(str.getChannel_id())) + "\">");
				writer.write("<display-name>" + StringEscapeUtils.escapeXml11(str.getChannel_name()) + "</display-name>");
				writer.write("</channel>");
			}
			writer.flush();
			writer.close();
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("An error occurred while writing to the file: " + e.getMessage());
		}
	}

	private static void writeProgramToFile(String filename, List<Programme> content) {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
			for (Programme str : content) {
				writer.write("<programme channel=\"" + str.getChannel_id() + "\" start=\"" + str.getStartEpoch()
						+ "\" stop=\"" + str.getEndEpoch() + "\">");
				writer.write("<title lang=\"en\">" + StringEscapeUtils.escapeXml11(str.getShowname()) + "</title>");
				writer.write("<desc lang=\"en\">" + StringEscapeUtils.escapeXml11(str.getDescription().trim()) + "</desc>");
				writer.write("<icon src=\"" + str.getEpisodePoster() + "\"/></programme>");
			}
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
			if (!file.delete()) {
				System.err.println("Failed to delete the file: " + filePath);
			}
		}
	}
}
