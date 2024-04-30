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

	public static void main(String[] args) {
		init();
	}

	public static void init() {
		String epgFile = "epg.xml.gz";
		boolean flag = false;
		System.out.println("Checking EPG file");
		File file = new File(epgFile);
		if (file.exists()) {
			// If file was modified today, don't generate new EPG
			// Else generate new EPG
			long lastModTime = file.lastModified();
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
			String fileDate = sdf.format(new Date(lastModTime));
			String todayDate = sdf.format(new Date());
			if (fileDate.equals(todayDate)) {
				System.out.println("EPG file is up to date.");
			} else {
				System.out.println("EPG file is old.");
				flag = true;
			}
		} else {
			System.out.println("EPG file doesn't exist");
			flag = true;
		}

		Runnable genepg = () -> {
			System.out.println("Generating new EPG file... Please wait.");
			try {
				genXMLGz(epgFile);
			} catch (IOException e) {
				e.printStackTrace();
			}
		};

		if (flag) {
			genepg.run();
		}
		System.exit(0);
	}

	public static void genXMLGz(String filename) throws IOException {
		System.out.println("Generating XML");
		deleteFile("temp");
		genXML();
		gzipFile("temp", "epg.xml.gz");
		deleteFile("temp");
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
		ExecutorService executor = Executors.newFixedThreadPool(20);

		for (Channel channel : channels) {
			executor.execute(() -> fetchEPG(channel));
		}

		executor.shutdown();
		try {
			executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}
		
		writeToFile("temp", "<tv version=\"\" encoding=\"\">");
		channels=channels.stream().sorted(Comparator.comparingInt(c -> c.getChannelLanguageId() == 8 ? 0 : c.getChannel_id())).collect(Collectors.toList());
		writeChannelToFile("temp", channels);
		//list.stream().sorted(Comparator.comparing(Programme::getChannel_id)).collect(Collectors.toList());
		writeProgramToFile("temp", list);
		writeToFile("temp", "</tv>");

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
			System.err.println("An error occurred while writing to the file: " + e.getMessage());
		}
	}

	public synchronized static void writeChannelToFile(String filename, List<Channel> content) {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
			for (Channel str : content) {
				writer.write("<channel id=\"" + str.getChannel_id() + "\">");
				writer.write("<display-name>" + str.getChannel_name() + "</display-name>");
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
				writer.write("<title lang=\"en\">" + str.getShowname() + "</title>");
				writer.write("<desc lang=\"en\">" + str.getDescription().replaceAll("\n", " ").trim() + "</desc>");
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
			if (file.delete()) {
				System.out.println("File deleted successfully: " + filePath);
			} else {
				System.err.println("Failed to delete the file: " + filePath);
			}
		}
	}
}
