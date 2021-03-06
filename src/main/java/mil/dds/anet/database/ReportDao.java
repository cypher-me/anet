package mil.dds.anet.database;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response.Status;

import org.joda.time.DateTime;
import org.skife.jdbi.v2.GeneratedKeys;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.Query;
import org.skife.jdbi.v2.TransactionCallback;
import org.skife.jdbi.v2.TransactionStatus;

import com.google.common.base.Joiner;

import mil.dds.anet.AnetObjectEngine;
import mil.dds.anet.beans.Organization;
import mil.dds.anet.beans.Organization.OrganizationType;
import mil.dds.anet.beans.Person;
import mil.dds.anet.beans.Poam;
import mil.dds.anet.beans.Report;
import mil.dds.anet.beans.Report.ReportState;
import mil.dds.anet.beans.ReportPerson;
import mil.dds.anet.beans.RollupGraph;
import mil.dds.anet.beans.lists.AbstractAnetBeanList.ReportList;
import mil.dds.anet.beans.search.OrganizationSearchQuery;
import mil.dds.anet.beans.search.ReportSearchQuery;
import mil.dds.anet.database.AdminDao.AdminSettingKeys;
import mil.dds.anet.database.mappers.PoamMapper;
import mil.dds.anet.database.mappers.ReportMapper;
import mil.dds.anet.database.mappers.ReportPersonMapper;
import mil.dds.anet.search.sqlite.SqliteReportSearcher;
import mil.dds.anet.utils.DaoUtils;
import mil.dds.anet.utils.Utils;

public class ReportDao implements IAnetDao<Report> {

	private static String[] fields = { "id", "state", "createdAt", "updatedAt", "engagementDate",
			"locationId", "approvalStepId", "intent", "exsum", "atmosphere", "cancelledReason",
			"advisorOrganizationId", "principalOrganizationId", "releasedAt",
			"atmosphereDetails", "text", "keyOutcomes",
			"nextSteps", "authorId"};
	private static String tableName = "reports";
	public static String REPORT_FIELDS = DaoUtils.buildFieldAliases(tableName, fields);

	Handle dbHandle;

	public ReportDao(Handle db) {
		this.dbHandle = db;
	}

	@Override
	public ReportList getAll(int pageNum, int pageSize) {
		String sql;
		if (DaoUtils.isMsSql(dbHandle)) {
			sql = "/* getAllReports */ SELECT " + REPORT_FIELDS + ", " + PersonDao.PERSON_FIELDS
					+ ", COUNT(*) OVER() AS totalCount FROM reports, people "
					+ "WHERE reports.authorId = people.id "
					+ "ORDER BY reports.createdAt DESC OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY";
		} else {
			sql = "/* getAllReports */ SELECT " + REPORT_FIELDS + ", " + PersonDao.PERSON_FIELDS
					+ "FROM reports, people "
					+ "WHERE reports.authorId = people.id "
					+ "ORDER BY reports.createdAt DESC LIMIT :limit OFFSET :offset";
		}
		Query<Report> query = dbHandle.createQuery(sql)
			.bind("limit", pageSize)
			.bind("offset", pageSize * pageNum)
			.map(new ReportMapper());
		return ReportList.fromQuery(query, pageNum, pageSize);
	}

	@Override
	public Report insert(Report r) {
		return dbHandle.inTransaction(new TransactionCallback<Report>() {
			@Override
			public Report inTransaction(Handle conn, TransactionStatus status) throws Exception {
				r.setCreatedAt(DateTime.now());
				r.setUpdatedAt(r.getCreatedAt());

				//MSSQL requires explicit CAST when a datetime2 might be NULL.
				StringBuilder sql = new StringBuilder("/* insertReport */ INSERT INTO reports "
						+ "(state, createdAt, updatedAt, locationId, intent, exsum, "
						+ "text, keyOutcomes, nextSteps, authorId, "
						+ "engagementDate, releasedAt, atmosphere, cancelledReason, "
						+ "atmosphereDetails, advisorOrganizationId, "
						+ "principalOrganizationId) VALUES "
						+ "(:state, :createdAt, :updatedAt, :locationId, :intent, "
						+ ":exsum, :reportText, :keyOutcomes, "
						+ ":nextSteps, :authorId, ");
				if (DaoUtils.isMsSql(dbHandle)) {
					sql.append("CAST(:engagementDate AS datetime2), CAST(:releasedAt AS datetime2), ");
				} else {
					sql.append(":engagementDate, :releasedAt, ");
				}
				sql.append(":atmosphere, :cancelledReason, :atmosphereDetails, :advisorOrgId, :principalOrgId)");

				GeneratedKeys<Map<String, Object>> keys = dbHandle.createStatement(sql.toString())
					.bindFromProperties(r)
					.bind("state", DaoUtils.getEnumId(r.getState()))
					.bind("atmosphere", DaoUtils.getEnumId(r.getAtmosphere()))
					.bind("cancelledReason", DaoUtils.getEnumId(r.getCancelledReason()))
					.bind("locationId", DaoUtils.getId(r.getLocation()))
					.bind("authorId", DaoUtils.getId(r.getAuthor()))
					.bind("advisorOrgId", DaoUtils.getId(r.getAdvisorOrg()))
					.bind("principalOrgId", DaoUtils.getId(r.getPrincipalOrg()))
					.executeAndReturnGeneratedKeys();
				r.setId(DaoUtils.getGeneratedId(keys));

				if (r.getAttendees() != null) {
					//Setify based on attendeeId to prevent violations of unique key constraint. 
					Map<Integer,ReportPerson> attendeeMap = new HashMap<Integer,ReportPerson>();
					r.getAttendees().stream().forEach(rp -> attendeeMap.put(rp.getId(), rp));
					for (ReportPerson p : attendeeMap.values()) {
						//TODO: batch this
						dbHandle.createStatement("/* insertReport.attendee */ INSERT INTO reportPeople "
								+ "(personId, reportId, isPrimary) VALUES (:personId, :reportId, :isPrimary)")
							.bind("personId", p.getId())
							.bind("reportId", r.getId())
							.bind("isPrimary", p.isPrimary())
							.execute();
					}
				}
				if (r.getPoams() != null) {
					for (Poam p : r.getPoams()) {
						//TODO: batch this.
						dbHandle.createStatement("/* insertReport.poam */ INSERT INTO reportPoams " 
								+ "(reportId, poamId) VALUES (:reportId, :poamId)")
							.bind("reportId", r.getId())
							.bind("poamId", p.getId())
							.execute();
					}
				}
				return r;
			}
		});
	}

	@Override
	public Report getById(int id) {
		Query<Report> query = dbHandle.createQuery("/* getReportById */ SELECT " + REPORT_FIELDS + ", " + PersonDao.PERSON_FIELDS
				+ "FROM reports, people "
				+ "WHERE reports.id = :id "
				+ "AND reports.authorId = people.id")
				.bind("id", id)
				.map(new ReportMapper());
		List<Report> results = query.list();
		if (results.size() == 0) { return null; }
		Report r = results.get(0);
		return r;
	}

	@Override
	public int update(Report r) {
		r.setUpdatedAt(DateTime.now());

		StringBuilder sql = new StringBuilder("/* updateReport */ UPDATE reports SET "
				+ "state = :state, updatedAt = :updatedAt, locationId = :locationId, "
				+ "intent = :intent, exsum = :exsum, text = :reportText, "
				+ "keyOutcomes = :keyOutcomes, nextSteps = :nextSteps, "
				+ "approvalStepId = :approvalStepId, ");
		if (DaoUtils.isMsSql(dbHandle)) {
			sql.append("engagementDate = CAST(:engagementDate AS datetime2), releasedAt = CAST(:releasedAt AS datetime2), ");
		} else {
			sql.append("engagementDate = :engagementDate, releasedAt = :releasedAt, ");
		}
		sql.append("atmosphere = :atmosphere, atmosphereDetails = :atmosphereDetails, "
				+ "cancelledReason = :cancelledReason, " 
				+ "principalOrganizationId = :principalOrgId, advisorOrganizationId = :advisorOrgId "
				+ "WHERE id = :id");

		return dbHandle.createStatement(sql.toString())
			.bindFromProperties(r)
			.bind("state", DaoUtils.getEnumId(r.getState()))
			.bind("locationId", DaoUtils.getId(r.getLocation()))
			.bind("authorId", DaoUtils.getId(r.getAuthor()))
			.bind("approvalStepId", DaoUtils.getId(r.getApprovalStep()))
			.bind("atmosphere", DaoUtils.getEnumId(r.getAtmosphere()))
			.bind("cancelledReason", DaoUtils.getEnumId(r.getCancelledReason()))
			.bind("advisorOrgId", DaoUtils.getId(r.getAdvisorOrg()))
			.bind("principalOrgId", DaoUtils.getId(r.getPrincipalOrg()))
			.execute();
	}

	public int addAttendeeToReport(ReportPerson rp, Report r) {
		return dbHandle.createStatement("/* addReportAttendee */ INSERT INTO reportPeople "
				+ "(personId, reportId, isPrimary) VALUES (:personId, :reportId, :isPrimary)")
			.bind("personId", rp.getId())
			.bind("reportId", r.getId())
			.bind("isPrimary", rp.isPrimary())
			.execute();
	}

	public int removeAttendeeFromReport(Person p, Report r) {
		return dbHandle.createStatement("/* deleteReportAttendee */ DELETE FROM reportPeople "
				+ "WHERE reportId = :reportId AND personId = :personId")
			.bind("reportId", r.getId())
			.bind("personId", p.getId())
			.execute();
	}

	public int updateAttendeeOnReport(ReportPerson rp, Report r) {
		return dbHandle.createStatement("/* updateAttendeeOnReport*/ UPDATE reportPeople "
				+ "SET isPrimary = :isPrimary WHERE reportId = :reportId AND personId = :personId")
			.bind("reportId", r.getId())
			.bind("personId", rp.getId())
			.bind("isPrimary", rp.isPrimary())
			.execute();
	}

	public int addPoamToReport(Poam p, Report r) {
		return dbHandle.createStatement("/* addPoamToReport */ INSERT INTO reportPoams (poamId, reportId) "
				+ "VALUES (:poamId, :reportId)")
			.bind("reportId", r.getId())
			.bind("poamId", p.getId())
			.execute();
	}

	public int removePoamFromReport(Poam p, Report r) {
		return dbHandle.createStatement("/* removePoamFromReport*/ DELETE FROM reportPoams "
				+ "WHERE reportId = :reportId AND poamId = :poamId")
				.bind("reportId", r.getId())
				.bind("poamId", p.getId())
				.execute();
	}

	public List<ReportPerson> getAttendeesForReport(int reportId) {
		return dbHandle.createQuery("/* getAttendeesForReport */ SELECT " + PersonDao.PERSON_FIELDS 
				+ ", reportPeople.isPrimary FROM reportPeople "
				+ "LEFT JOIN people ON reportPeople.personId = people.id "
				+ "WHERE reportPeople.reportId = :reportId")
			.bind("reportId", reportId)
			.map(new ReportPersonMapper())
			.list();
	}

	public List<Poam> getPoamsForReport(Report report) {
		return dbHandle.createQuery("/* getPoamsForReport */ SELECT * FROM poams, reportPoams "
				+ "WHERE reportPoams.reportId = :reportId "
				+ "AND reportPoams.poamId = poams.id")
				.bind("reportId", report.getId())
				.map(new PoamMapper())
				.list();
	}

	//Does an unauthenticated search. This will never return any DRAFT or REJECTED reports
	public ReportList search(ReportSearchQuery query) { 
		return search(query, null);
	}
	
	public ReportList search(ReportSearchQuery query, Person user) {
		return AnetObjectEngine.getInstance().getSearcher().getReportSearcher()
			.runSearch(query, dbHandle, user);
	}

	/*
	 * Deletes a given report from the database. 
	 * Ensures consistency by removing all references to a report before deleting a report. 
	 */
	public void deleteReport(final Report report) {
		dbHandle.inTransaction(new TransactionCallback<Void>() {
			public Void inTransaction(Handle conn, TransactionStatus status) throws Exception {
				//Delete poams
				dbHandle.execute("/* deleteReport.poams */ DELETE FROM reportPoams where reportId = ?", report.getId());
				
				//Delete attendees
				dbHandle.execute("/* deleteReport.attendees */ DELETE FROM reportPeople where reportId = ?", report.getId());
				
				//Delete comments
				dbHandle.execute("/* deleteReport.comments */ DELETE FROM comments where reportId = ?", report.getId());
				
				//Delete approvalActions
				dbHandle.execute("/* deleteReport.actions */ DELETE FROM approvalActions where reportId = ?", report.getId());
				
				//Delete report
				dbHandle.execute("/* deleteReport.report */ DELETE FROM reports where id = ?", report.getId());
				
				return null;
			}
		});
		
	}

	private DateTime getRollupEngagmentStart(DateTime start) { 
		String maxReportAgeStr = AnetObjectEngine.getInstance().getAdminSetting(AdminSettingKeys.DAILY_ROLLUP_MAX_REPORT_AGE_DAYS);
		if (maxReportAgeStr == null) { 
			throw new WebApplicationException("Missing Admin Setting for " + AdminSettingKeys.DAILY_ROLLUP_MAX_REPORT_AGE_DAYS); 
		} 
		Integer maxReportAge = Integer.parseInt(maxReportAgeStr);
		return start.minusDays(maxReportAge);
	}
	
	/* Generates the Rollup Graph for a particular Organization Type, starting at the root of the org hierarchy */
	public List<RollupGraph> getDailyRollupGraph(DateTime start, DateTime end, OrganizationType orgType) {
		List<Map<String, Object>> results = rollupQuery(start, end, orgType, null, false);
		Map<Integer,Organization> orgMap = AnetObjectEngine.getInstance().buildTopLevelOrgHash(orgType);
		
		return generateRollupGraphFromResults(results, orgMap);
	}
	
	/* Generates a Rollup graph for a particular organiztaion.  Starting with a given parent Organization */
	public List<RollupGraph> getDailyRollupGraph(DateTime start, DateTime end, Integer parentOrgId, OrganizationType orgType) {
		List<Organization> orgList = null;
		Map<Integer,Organization> orgMap;
		if (parentOrgId.equals(-1) == false) { // -1 is code for no parent org.  
			//doing this as two separate queries because I do need all the information about the organizations
			OrganizationSearchQuery query = new OrganizationSearchQuery();
			query.setParentOrgId(parentOrgId);
			query.setParentOrgRecursively(true);
			query.setPageSize(Integer.MAX_VALUE);
			orgList = AnetObjectEngine.getInstance().getOrganizationDao().search(query).getList();
			Optional<Organization> parentOrg = orgList.stream().filter(o -> o.getId().equals(parentOrgId)).findFirst();
			if (parentOrg.isPresent() == false) { 
				throw new WebApplicationException("No such organization with id " + parentOrgId, Status.NOT_FOUND);
			}
			orgMap  = Utils.buildParentOrgMapping(orgList, parentOrgId);
		} else { 
			orgMap = new HashMap<Integer, Organization>(); //guaranteed to match no orgs! 
		}
		
		List<Map<String,Object>> results = rollupQuery(start, end, orgType, orgList, parentOrgId.equals(-1));
		
		return generateRollupGraphFromResults(results, orgMap);
	}
	
	/** Helper method that builds and executes the daily rollup query
	 * Handles both MsSql and Sqlite
	 * Searching for just all reports and for reports in certain organizations.
	 * @param orgType: the type of organization Id to be lookinf ro 
	 * @param orgs: the list of orgs for whose reports to find, null means all
	 * @param missingOrgReports: true if we want to look for reports specifically with NULL org Ids. 
	 */
	private List<Map<String,Object>> rollupQuery(DateTime start, 
			DateTime end, 
			OrganizationType orgType, 
			List<Organization> orgs, 
			boolean missingOrgReports) { 
		String orgColumn = orgType == OrganizationType.ADVISOR_ORG ? "advisorOrganizationId" : "principalOrganizationId";
		Map<String,Object> sqlArgs = new HashMap<String,Object>();
		
		StringBuilder sql = new StringBuilder();
		sql.append("/* RollupQuery */ SELECT " + orgColumn + " as orgId, state, count(*) AS count ");
		sql.append("FROM reports WHERE ");
		
		if (DaoUtils.isMsSql(dbHandle)) { 
			sql.append("releasedAt >= :startDate and releasedAt <= :endDate "
					+ "AND engagementDate > :engagementDateStart ");
			sqlArgs.put("startDate", start);
			sqlArgs.put("endDate", end);
			sqlArgs.put("engagementDateStart", getRollupEngagmentStart(start));
		} else { 
			sql.append("releasedAt  >= DateTime(:startDate) AND releasedAt <= DateTime(:endDate) " 
					+ "AND engagementDate > DateTime(:engagementDateStart) ");
			sqlArgs.put("startDate", SqliteReportSearcher.sqlitePattern.print(start));
			sqlArgs.put("endDate", SqliteReportSearcher.sqlitePattern.print(end));
			sqlArgs.put("engagementDateStart", SqliteReportSearcher.sqlitePattern.print(getRollupEngagmentStart(start)));
		}
		
		if (orgs != null) { 
			List<String> sqlBind = new LinkedList<String>();
			int orgNum = 0; 
			for (Organization o : orgs) { 
				sqlArgs.put("orgId" + orgNum, o.getId());
				sqlBind.add(":orgId" + orgNum);
				orgNum++;
			}
			String orgInSql = Joiner.on(',').join(sqlBind);
			sql.append("AND " + orgColumn + " IN (" + orgInSql + ") ");
		} else if (missingOrgReports) { 
			sql.append(" AND " + orgColumn + " IS NULL ");
		}
		
		sql.append("GROUP BY " + orgColumn + ", state");
		

		return dbHandle.createQuery(sql.toString())
			.bindFromMap(sqlArgs)
			.list();
	}
	
	/* Given the results from the database on the number of reports grouped by organization
	 * And the map of each organization to the organization that their reports roll up to
	 * this method returns the final rollup graph information. 
	 */
	private List<RollupGraph> generateRollupGraphFromResults(List<Map<String,Object>> dbResults, Map<Integer, Organization> orgMap) { 
		Map<Integer,Map<ReportState,Integer>> rollup = new HashMap<Integer,Map<ReportState,Integer>>();
		
		for (Map<String,Object> result : dbResults) { 
			Integer orgId = (Integer) result.get("orgId");
			Integer count = (Integer) result.get("count");
			ReportState state = ReportState.values()[(Integer) result.get("state")];
		
			Integer parentOrgId = (orgId == null) ? null : DaoUtils.getId(orgMap.get(orgId));
			Map<ReportState,Integer> orgBar = rollup.get(parentOrgId);
			if (orgBar == null) { 
				orgBar = new HashMap<ReportState,Integer>();
				rollup.put(parentOrgId,  orgBar);
			}
			orgBar.put(state,  Utils.orIfNull(orgBar.get(state), 0) + count);
		}
		
		List<RollupGraph> result = new LinkedList<RollupGraph>();
		for (Map.Entry<Integer, Map<ReportState,Integer>> entry : rollup.entrySet()) { 
			Map<ReportState,Integer> values = entry.getValue();
			RollupGraph bar = new RollupGraph();
			bar.setOrg(orgMap.get(entry.getKey()));
			bar.setReleased(Utils.orIfNull(values.get(ReportState.RELEASED), 0));
			bar.setCancelled(Utils.orIfNull(values.get(ReportState.CANCELLED), 0));
			result.add(bar);
		}
		
		return result;
	}

}
