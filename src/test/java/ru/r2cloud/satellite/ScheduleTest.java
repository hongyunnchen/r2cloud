package ru.r2cloud.satellite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ru.r2cloud.CelestrakServer;
import ru.r2cloud.TestConfiguration;
import ru.r2cloud.TestUtil;
import ru.r2cloud.model.ObservationRequest;
import ru.r2cloud.model.Satellite;
import ru.r2cloud.predict.PredictOreKit;
import ru.r2cloud.tle.CelestrakClient;
import ru.r2cloud.tle.TLEDao;

public class ScheduleTest {

	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	private Schedule schedule;
	private CelestrakServer celestrak;
	private TestConfiguration config;
	private SatelliteDao satelliteDao;
	private long current;

	@Test(expected = IllegalArgumentException.class)
	public void testInvalidObservationId() throws Exception {
		schedule.assignTasksToSlot(UUID.randomUUID().toString(), new ScheduledObservation(null, null, null, null));
	}

	@Test
	public void testEdgeCases() throws Exception {
		assertNull(schedule.cancel(null));
		assertNull(schedule.cancel(UUID.randomUUID().toString()));
	}

	@Test
	public void testBasicOperations() throws Exception {
		List<ObservationRequest> expected = readExpected("expected/schedule.txt");
		List<ObservationRequest> actual = schedule.createInitialSchedule(extractSatellites(expected, satelliteDao), current);
		assertObservations(expected, actual);

		ObservationRequest first = schedule.findFirstBySatelliteId("40378", getTime("2020-10-01 11:38:34.491"));
		assertEquals("1601553418714", first.getId());
		assertNull(schedule.findFirstBySatelliteId("40378", getTime("2020-10-02 11:43:56.801")));

		// tasks and ensure previous got cancelled
		ScheduledObservation tasks = new ScheduledObservation(null, null, null, null);
		schedule.assignTasksToSlot(first.getId(), tasks);
		schedule.assignTasksToSlot(first.getId(), tasks);
		assertFalse(tasks.isCancelled());
		ScheduledObservation differentTasks = new ScheduledObservation(null, null, null, null);
		schedule.assignTasksToSlot(first.getId(), differentTasks);
		assertTrue(tasks.isCancelled());

		List<ObservationRequest> sublist = schedule.findObservations(getTime("2020-10-01 10:55:40.000"), getTime("2020-10-01 13:04:14.000"));
		assertObservations(readExpected("expected/scheduleSublist.txt"), sublist);

		List<ObservationRequest> noaa18 = schedule.addSatelliteToSchedule(satelliteDao.findByName("NOAA 18"), current);
		List<ObservationRequest> extended = new ArrayList<>(actual);
		extended.addAll(noaa18);
		assertObservations(readExpected("expected/scheduleWithNoaa18.txt"), extended);
		// test satellite already scheduled
		List<ObservationRequest> doubleAdded = schedule.addSatelliteToSchedule(satelliteDao.findByName("NOAA 18"), current);
		assertObservations(noaa18, doubleAdded);

		// cancel all newly added
		for (ObservationRequest cur : noaa18) {
			ScheduledObservation curTasks = new ScheduledObservation(null, null, null, null);
			schedule.assignTasksToSlot(cur.getId(), curTasks);
			schedule.cancel(cur.getId());
			assertTrue(curTasks.isCancelled());
		}
		assertObservations(expected, actual);

		schedule.cancelAll();
		assertTrue(differentTasks.isCancelled());
		actual = schedule.createInitialSchedule(extractSatellites(expected, satelliteDao), current);
		assertObservations(expected, actual);

		first = schedule.findFirstBySatelliteId("40378", getTime("2020-10-01 11:38:34.491"));
		// slot is occupied
		assertNull(schedule.moveObservation(first, getTime("2020-10-02 11:11:00.359")));
		first = schedule.findFirstBySatelliteId("40378", getTime("2020-10-01 11:38:34.491"));
		ObservationRequest movedTo = schedule.moveObservation(first, getTime("2020-10-02 00:00:00.000"));
		assertNotNull(movedTo);
		assertEquals(getTime("2020-10-02 00:00:00.000"), movedTo.getStartTimeMillis());
		assertEquals(getTime("2020-10-02 00:05:37.617"), movedTo.getEndTimeMillis());

		schedule.cancelAll();
		long partialStart = getTime("2020-09-30 23:00:46.872");
		actual = schedule.createInitialSchedule(extractSatellites(expected, satelliteDao), partialStart);
		assertEquals(partialStart, actual.get(0).getStartTimeMillis());
	}

	@Before
	public void start() throws Exception {
		celestrak = new CelestrakServer();
		celestrak.start();
		celestrak.mockResponse(TestUtil.loadExpected("tle-2020-09-27.txt"));
		config = new TestConfiguration(tempFolder);
		config.setProperty("locaiton.lat", "51.49");
		config.setProperty("locaiton.lon", "0.01");
		PredictOreKit predict = new PredictOreKit(config);
		satelliteDao = new SatelliteDao(config);
		TLEDao tleDao = new TLEDao(config, satelliteDao, new CelestrakClient(celestrak.getUrl()));
		tleDao.start();
		ObservationFactory factory = new ObservationFactory(predict, tleDao, config);

		schedule = new Schedule(factory);

		current = getTime("2020-09-30 22:17:01.000");
	}

	@After
	public void stop() {
		if (celestrak != null) {
			celestrak.stop();
		}
	}

	private static void assertObservations(List<ObservationRequest> expected, List<ObservationRequest> actual) {
		assertEquals(expected.size(), actual.size());
		Collections.sort(expected, ObservationRequestComparator.INSTANCE);
		Collections.sort(actual, ObservationRequestComparator.INSTANCE);
		for (int i = 0; i < expected.size(); i++) {
			assertObservation(expected.get(i), actual.get(i));
		}
	}

	private static void assertObservation(ObservationRequest expected, ObservationRequest actual) {
		assertEquals(expected.getSatelliteId(), actual.getSatelliteId());
		assertEquals(expected.getStartTimeMillis(), actual.getStartTimeMillis());
		assertEquals(expected.getEndTimeMillis(), actual.getEndTimeMillis());
	}

	private static List<Satellite> extractSatellites(List<ObservationRequest> req, SatelliteDao dao) throws Exception {
		Set<String> ids = new HashSet<>();
		for (ObservationRequest cur : req) {
			ids.add(cur.getSatelliteId());
		}
		List<Satellite> result = new ArrayList<>();
		for (String cur : ids) {
			result.add(dao.findById(cur));
		}
		return result;
	}

	private static List<ObservationRequest> readExpected(String filename) throws Exception {
		List<ObservationRequest> result = new ArrayList<>();
		SimpleDateFormat sdf = createDateFormatter();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(ScheduleTest.class.getClassLoader().getResourceAsStream(filename)))) {
			String curLine = null;
			Pattern COMMA = Pattern.compile(",");
			while ((curLine = r.readLine()) != null) {
				String[] parts = COMMA.split(curLine);
				ObservationRequest cur = new ObservationRequest();
				cur.setSatelliteId(parts[1]);
				cur.setStartTimeMillis(sdf.parse(parts[2]).getTime());
				cur.setEndTimeMillis(sdf.parse(parts[3]).getTime());
				result.add(cur);
			}
		}
		return result;
	}

	private static SimpleDateFormat createDateFormatter() {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		return sdf;
	}

	private static long getTime(String str) throws Exception {
		return createDateFormatter().parse(str).getTime();
	}
}
