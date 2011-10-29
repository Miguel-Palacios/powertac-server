/*
 * Copyright (c) 2011 by the original author
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.powertac.server;

/**
 *  This is the Power TAC simulator weather service that queries an existing
 *  weather server for weather data and serves it to the brokers logged into 
 *  the game.
 *  
 * @author Erik Onarheim
 */

// Import network java
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

// Import common
import org.apache.log4j.Logger;
import org.joda.time.Instant;
import org.powertac.common.Competition;
import org.powertac.common.PluginConfig;
import org.powertac.common.TimeService;
import org.powertac.common.Timeslot;
import org.powertac.common.WeatherForecastPrediction;
import org.powertac.common.WeatherReport;
import org.powertac.common.WeatherForecast;
import org.powertac.common.interfaces.InitializationService;
import org.powertac.common.interfaces.TimeslotPhaseProcessor;
import org.powertac.common.repo.PluginConfigRepo;
import org.powertac.common.repo.TimeslotRepo;
import org.powertac.common.repo.WeatherForecastRepo;
import org.powertac.common.repo.WeatherReportRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

//TODO: Create issue Asynchronous and Blocking modes that expose the flags
//TODO: Create log messages in weatherService
//TODO: Implement WeatherTests with some default data
//TODO: XML serialization test for WeatherReport and WeatherForecast WeatherForecastPrediction, see org.powertac.common.msg tests (JUnit4 tests)
//TODO: Repo tests copy those
//TODO: WeatherService Tests BEEANS!!
//TODO: Pull request Tests, WeatherService, Repos
//TODO: Basic JSF MVC application
//XTODO: Switch implements to extends in timeslotphaseprocessor
//XTODO: Plugin Config object for weatherServers indicating location and date range, place in PluginConfigRepo

@Service
public class WeatherService extends TimeslotPhaseProcessor implements
		InitializationService {

	static private Logger log = Logger
			.getLogger(WeatherService.class.getName());
	private int simulationPhase = 1;
	private int weatherReqInterval = 12;
	private int daysOut = 1;
	private int currentWeatherId = 1;
	private String serverUrl = "http://tac06.cs.umn.edu:8080/powertac-weather-server/weatherSet/weatherRequest?id=0&setname=default&weather_days=1&weather_id=";
	private boolean requestFailed = false;

	@Autowired
	private TimeService timeService;

	@Autowired
	private TimeslotRepo timeslotRepo;

	@Autowired
	private PluginConfigRepo pluginConfigRepo;

	@Autowired
	private CompetitionControlService competitionControlService;

	@Autowired
	private WeatherReportRepo weatherReportRepo;

	@Autowired
	private WeatherForecastRepo weatherForecastRepo;

	// public WeatherService() {
	// super();
	// }

	public void init(PluginConfig config) {
		super.init();
	}

	// Make actual web request to the weather-server
	public void activate(Instant time, int phaseNumber) {
		// Error check the request interval
		if (weatherReqInterval > 24) {
			// log.error("weather request interval ${weatherRequestInterval} > 24 hr"
			weatherReqInterval = 24;
		}

		long msec = time.getMillis();// timeService.getCurrentTime().getMillis();

		if (msec % (weatherReqInterval * TimeService.HOUR) == 0) {
			System.out.println("Grabbing weather data"); // TODO

			// time to publish
			// log.info "Requesting Weather from " + serverUrl + "=" +
			// currentWeatherId + " at time: " + time

			// Attempt to make a web request to the weather server
			try {
				// Need try/catch for invalid host strings

				// currentWeatherId+=(2*weatherRequestInterval) // 2 weather
				// reports per hour
				webRequest(timeslotRepo.currentTimeslot(), 1); // TODO: Should
																// be fixed to
																// int
				requestFailed = false;

			} catch (Throwable e) {
				// log.error "Unable to connect to host: " + serverUrl
				requestFailed = true;

			}
		} else {
			System.out.println("Not grabbing weather: " + msec + " % "
					+ (weatherReqInterval * TimeService.HOUR)); // TODO
		}

	}

	// Forecasts are random and must be repeatable from the same seed
	private boolean webRequest(Timeslot time, int randomSeed) {
		Timeslot currentTime = time;
		boolean readingForecast = false;

		List<String[]> reportValues = new ArrayList<String[]>();
		List<String[]> forecastValues = new ArrayList<String[]>();

		try {
			// Create a URLConnection object for a URL and send request
			URL url = new URL(serverUrl + currentWeatherId);

			URLConnection conn = url.openConnection();

			// Get the response
			BufferedReader input = new BufferedReader(new InputStreamReader(
					conn.getInputStream()));

			String tmpLine;
			while ((tmpLine = input.readLine()) != null) {
				System.out.println(tmpLine);

				String[] weatherValue;
				// Parse weather reports here
				if (tmpLine.trim().compareTo("---Forecast Data---") == 0) {
					// Set mode to reading forecast
					readingForecast = true;
				} else {
					// Remove brackets from response
					tmpLine.replace("[", "");
					tmpLine.replace("]", "");

					// Reading values
					weatherValue = tmpLine.split(", ");
					for (int i = 0; i < weatherValue.length; i++) {
						// System.out.println("Parsing: " + weatherValue[i]);
						weatherValue[i] = weatherValue[i].split(":")[1].trim();
					}
					if (!readingForecast) {
						reportValues.add(weatherValue.clone());
					} else {
						forecastValues.add(weatherValue.clone());
					}
				}
			}
			input.close();
		} catch (Exception e) {
			log.error("Exception Raised during newtork call: " + e.toString());
			System.out.println("Exception Raised: " + e.toString());
			return false;
		}
		System.out.println("About to add to repo");
		for (String[] v : reportValues) {
			WeatherReport newReport = new WeatherReport(currentTime,
					Double.parseDouble(v[0]),// temperature,
					Double.parseDouble(v[1]),// windSpeed,
					Double.parseDouble(v[2]),// windDirection,
					Double.parseDouble(v[3]));// cloudCover

			// Add a report to the repo, increment to the next timeslot
			weatherReportRepo.add(newReport);
			System.out.println("Report num: " + v[0]);
			if(currentTime == null){
				System.out.println("Null timeslot");
			}
			currentTime = currentTime.getNext();
			
		}
		System.out.println("Read all reports...");
		//Reset time for corresponding forecasts
		currentTime = time;

		List<WeatherForecastPrediction> currentPredictions;
		String[] currentPred;
		for (int i = 1; i <= 2 * weatherReqInterval; i++) {
			currentPredictions = new ArrayList<WeatherForecastPrediction>();
			for (int j = 1; j < 47; j++) {
				currentPred = forecastValues.get(i + j);
				currentPredictions.add(new WeatherForecastPrediction(j, Double
						.parseDouble(currentPred[0]),// temperature,
						Double.parseDouble(currentPred[1]),// windSpeed,
						Double.parseDouble(currentPred[2]),// windDirection,
						Double.parseDouble(currentPred[3])));// cloudCover
				System.out.println("Read prediction: " + i + ", " + j);
			}
			WeatherForecast newForecast = new WeatherForecast(currentTime,
					currentPredictions);
			//Add a forecast to the repo, increment to the next timeslot
			weatherForecastRepo.add(newForecast);
			currentTime = currentTime.getNext();
		}

		return true;
	}

	public void setDefaults() {
		pluginConfigRepo.makePluginConfig("weatherService", "init")
				.addConfiguration("location", "Minneapolis")
				.addConfiguration("dateRangeStart", "10-10-2009")
				.addConfiguration("dateRangeEnd", "12-10-2009");
	}

	public String initialize(Competition competition,
			List<String> completedInits) {
		PluginConfig weatherServiceConfig = pluginConfigRepo
				.findByRoleName("weatherService");
		if (weatherServiceConfig == null) {
			log.error("PluginConfig for WeatherService does not exist");
		} else {
			this.init(weatherServiceConfig);
			return "WeatherService";
		}
		return "fail";

	}

}
