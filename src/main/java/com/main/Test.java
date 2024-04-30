package com.main;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class Test {
	public static void main(String[] args) {
		long timestampInMillis = 1714395600000L;
        
        // Convert Unix timestamp to ZonedDateTime
        ZonedDateTime dateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestampInMillis), ZoneId.of("Asia/Kolkata"));
        
        // Format ZonedDateTime with desired format
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z");
        String formattedDateTime = dateTime.format(formatter);
        
        System.out.println("Converted timestamp: " + formattedDateTime);
	}
}
