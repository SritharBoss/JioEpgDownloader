package com.main;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class Programme {
	private int channel_id;
	private String startEpoch;
	private String endEpoch;
	private String showname;
	private String description;
	private String episodePoster;

	public int getChannel_id() {
		return channel_id;
	}

	public String getStartEpoch() {
		
		try {
			ZonedDateTime dateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(Long.valueOf(startEpoch)),
					ZoneId.of("Asia/Kolkata"));
			return dateTime.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z"));
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println(startEpoch);
			return startEpoch;
		}

	}

	public String getEndEpoch() {
		try {
			ZonedDateTime dateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(Long.valueOf(endEpoch)),
					ZoneId.of("Asia/Kolkata"));
			return dateTime.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z"));
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println(endEpoch);
			return endEpoch;
		}
	}

	public String getShowname() {
		return showname;
	}

	public String getDescription() {
		return description;
	}

	public String getEpisodePoster() {
		return episodePoster;
	}
}
