package apttrx;

import static marmot.optor.AggregateFunction.AVG;
import static marmot.optor.AggregateFunction.COUNT;
import static marmot.optor.AggregateFunction.MAX;
import static marmot.optor.AggregateFunction.MIN;
import static marmot.optor.AggregateFunction.SUM;
import static marmot.optor.geo.SpatialRelation.INTERSECTS;

import org.apache.log4j.PropertyConfigurator;

import common.SampleUtils;
import marmot.DataSet;
import marmot.MarmotRuntime;
import marmot.Plan;
import marmot.RecordSchema;
import marmot.command.MarmotCommands;
import marmot.remote.RemoteMarmotConnector;
import marmot.remote.robj.MarmotClient;
import utils.CommandLine;
import utils.CommandLineParser;
import utils.StopWatch;

/**
 * 
 * @author Kang-Woo Lee (ETRI)
 */
public class SummarizeByHighSchoolLong {
	private static final String APT_TRX = "주택/실거래/아파트매매";
	private static final String SCHOOLS = "POI/전국초중등학교";
	private static final String HIGH_SCHOOLS = "tmp/아파트실매매/고등학교";
	private static final String RESULT = "tmp/result";
	
	public static final void main(String... args) throws Exception {
		PropertyConfigurator.configure("log4j.properties");
		
		CommandLineParser parser = new CommandLineParser("mc_list_records ");
		parser.addArgOption("host", "ip_addr", "marmot server host (default: localhost)", false);
		parser.addArgOption("port", "number", "marmot server port (default: 12985)", false);
		
		CommandLine cl = parser.parseArgs(args);
		if ( cl.hasOption("help") ) {
			cl.exitWithUsage(0);
		}

		String host = MarmotCommands.getMarmotHost(cl);
		int port = MarmotCommands.getMarmotPort(cl);
		
		StopWatch watch = StopWatch.start();
		
		// 원격 MarmotServer에 접속.
		RemoteMarmotConnector connector = new RemoteMarmotConnector();
		MarmotClient marmot = connector.connect(host, port);

		Plan plan;
		
		//전국초중등학교 정보에서 고등학교만 뽑는다.
		DataSet highSchools = marmot.getDataSetOrNull(HIGH_SCHOOLS);
		if ( highSchools == null ) {
			highSchools = findHighSchool(marmot);
		}
		String geomCol = highSchools.getGeometryColumn();
		String srid = highSchools.getSRID();
		
		plan = marmot.planBuilder("summarize_by_school")
						.load(APT_TRX)

						// 지오코딩을 위해 대상 아파트의 지번주소 구성
						.expand("addr:string", "addr = 시군구 + ' ' + 번지 + ' ' + 단지명")
						// 지오코딩과 관련없는 컬럼 제거
						.project("addr,시군구,번지,단지명")
						// 중복된 아파트 주소를 제거
						// 지오코딩에 소요시간이 많이들기 때문에, distinct시 강제로 많은 수의
						// partition으로 나눠서 수행하도록한다.
						// 이렇게 되면 다음에 수행되는 지오코딩이 각 partition별로
						// 수행되기 때문에 높은 병렬성을 갖게된다.
						.distinct("addr", 37)
						// 지오코딩을 통해 아파트 좌표 계산
						.lookupPostalAddress("addr", "info")
						.expand("the_geom:multi_polygon", "the_geom = info.?geometry")
						
						// 고등학교 주변 1km 내의 아파트 검색.
						.centroid("the_geom", "the_geom")
						.buffer("the_geom", "circle", 1000)
						.spatialJoin("circle", HIGH_SCHOOLS, INTERSECTS,
									String.format("*-{the_geom},param.{%s,id,name}",geomCol))
						
						// 고등학교 1km내 위치에 해당하는 아파트 거래 정보를 검색.
						.join("시군구,번지,단지명", APT_TRX, "시군구,번지,단지명",
								"the_geom,id,name,param.*", null)
						// 평당 거래액 계산.
						.expand("평당거래액:int",
								"평당거래액 = (int)Math.round((거래금액*3.3) / 전용면적)")
						
						// 고등학교를 기준으로 그룹핑하여 집계한다.
						.groupBy("id")
						.taggedKeyColumns("the_geom,name")
						.aggregate(COUNT().as("거래건수"),
									SUM("거래금액").as("총거래액"),
									AVG("평당거래액").as("평당거래액"),
									MAX("거래금액").as("최대거래액"),
									MIN("거래금액").as("최소거래액"))
						.expand("평당거래액:int", "평당거래액=평당거래액")
						.sort("평당거래액:D")
						
						.store(RESULT)
						.build();
		
		RecordSchema schema = marmot.getOutputRecordSchema(plan);
		DataSet result = marmot.createDataSet(RESULT, schema, "the_geom", srid, true);
		marmot.execute(plan);
		watch.stop();

		SampleUtils.printPrefix(result, 3);
		System.out.printf("elapsed: %s%n", watch.getElapsedTimeString());
	}
	
	private static DataSet findHighSchool(MarmotRuntime marmot) {
		DataSet ds = marmot.getDataSet(SCHOOLS);
		String geomCol = ds.getGeometryColumn();
		String srid = ds.getSRID();
	
		Plan plan = marmot.planBuilder("find_high_school")
							.load(SCHOOLS)
							.filter("type == '고등학교'")
							.store(HIGH_SCHOOLS)
							.build();
		RecordSchema schema = marmot.getOutputRecordSchema(plan);
		DataSet result = marmot.createDataSet(HIGH_SCHOOLS, schema, geomCol, srid, true);
		marmot.execute(plan, false);
		return result;
	}
}
