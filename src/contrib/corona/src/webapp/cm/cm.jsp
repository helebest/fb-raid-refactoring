<%@ page
  contentType="text/html; charset=UTF-8"
  import="javax.servlet.*"
  import="javax.servlet.http.*"
  import="java.io.*"
  import="java.text.*"
  import="java.util.*"
  import="org.apache.hadoop.corona.*"
  import="org.apache.hadoop.util.ServletUtil"
  import="org.apache.hadoop.util.StringUtils"
%>

<%!public void generateSummaryTable(JspWriter out, NodeManager nm, SessionManager sm) 
  throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("<table border=\"1\" cellpadding=\"5\" cellspacing=\"0\">\n");

    // populate header row with list of resource types
    sb.append("<tr>");

    List<String> resourceTypes = nm.getResourceTypes();
    String [] perTypeColumns = {"Running", "Waiting", "TotalSlots", "FreeSlots"};

    sb.append(generateTableHeaderCells(resourceTypes, perTypeColumns.length));
    sb.append("<th colspan=1>Nodes</th>");
    sb.append("<th colspan=1>Session</th>");
    sb.append("</tr>\n");
    out.print(sb.toString());

    String typeColHeader = "<td>" + org.apache.commons.lang.StringUtils.join(perTypeColumns, "</td><td>") + "</td>";
    StringBuilder row = new StringBuilder("<tr>");
    for(String resourceType: resourceTypes) {
      row.append(typeColHeader);
    }

    row.append("<td>Total</td><td>Running</td></tr>\n");
    out.print(row.toString());


    row = new StringBuilder("<tr>");

    for(String resourceType: resourceTypes) {
      int waiting = sm.getPendingRequestCountForType(resourceType);
      int running = sm.getRequestCountForType(resourceType) - waiting;
      int totalslots = nm.getMaxCpuForType(resourceType);
      int freeslots = totalslots - nm.getAllocatedCpuForType(resourceType);
      row.append("<td>" + running + "</td>");
      row.append("<td>" + waiting + "</td>");
      row.append("<td>" + totalslots + "</td>");
      row.append("<td>" + freeslots + "</td>");
    }

    row.append("<td>" + nm.getTotalNodeCount() + "</td>");
    row.append("<td>" + sm.getRunningSessionCount() + "</td>");
    row.append("</tr>\n");
    row.append("</table>\n");

    out.print(row.toString());
    out.print("<br>");
  }

  public void generateActiveSessionTable(JspWriter out, NodeManager nm, SessionManager sm)
  throws IOException {

    StringBuilder sb = new StringBuilder();
    sb.append("<table border=\"1\" cellpadding=\"5\" cellspacing=\"0\">\n");

    // populate header row
    sb.append("<tr>");

    // fixed headers
    String [] fixedHeaders = {"Id", "User", "Pool", "Name"};
    sb.append(generateTableHeaderCells(Arrays.asList(fixedHeaders), 1));

    // header per type
    List<String> resourceTypes = nm.getResourceTypes();
    Collections.sort(resourceTypes);
    String [] perTypeColumns = {"Running", "Waiting", "Total"};
    sb.append(generateTableHeaderCells(resourceTypes, perTypeColumns.length));

    sb.append("</tr>\n");

    // populate sub-header row
    sb.append("<tr>");
    for (int i=0; i< fixedHeaders.length; i++) {
      sb.append("<td></td>");
    }
    for (String resourceType: resourceTypes) {
      for (int i=0; i<perTypeColumns.length; i++)
        sb.append("<td>" + perTypeColumns[i] + "</td>");
    }
    sb.append("</tr>\n");

    for(String id: sm.getSessions()) {
      Session s;
      try { s = sm.getSession(id); } catch (InvalidSessionHandle e) { continue; }

      synchronized (s) {
        if (s.deleted)
          continue;

        String url = "<a href=\"" + s.getUrl() + "\">" + id + "</a>";
        sb.append("<tr><td>" + url + "</td><td>" + s.getUserId() + "</td><td>"
            + PoolManager.getPoolName(s) + "</td><td>"+ s.getName() + "</td>");
        for (String resourceType: resourceTypes) {
          int total = s.getRequestCountForType(resourceType);
          int waiting = s.getPendingRequestForType(resourceType).size();
          int running = s.getGrantedRequestForType(resourceType).size();
          sb.append("<td>" + running + "</td><td>" + waiting + "</td><td>" + total + "</td>");
        }
        sb.append("</tr>\n");
      }
    }
    out.print(sb.toString());
    out.print("</table><br>");
  }

  public void generatePoolTable(
      JspWriter out, Scheduler scheduler, Collection<String> types) throws IOException {
    List<String> typeList = new ArrayList<String>(types.size());
    typeList.addAll(types);
    Collections.sort(typeList);
    List<String> metricsNames = new ArrayList<String>();
    for (PoolMetrics.MetricName name : PoolMetrics.MetricName.values()) {
      metricsNames.add(name.toString());
    }
    StringBuilder sb = new StringBuilder();
    sb.append("<table border=\"1\" cellpadding=\"5\" cellspacing=\"0\">\n");

    // Generate headers
    sb.append("<tr>");
    sb.append("<th>id</th>");
    sb.append(generateTableHeaderCells(typeList, metricsNames.size()));
    sb.append("</tr>\n");

    sb.append("<tr>");
    sb.append("<td></td>");
    for (int i = 0; i < typeList.size(); ++i) {
      for (String name : metricsNames) {
        sb.append("<td>" + name + "</td>");
      }
    }
    sb.append("</tr>\n");

    for (String poolName : scheduler.getPoolNames()) {
      sb.append("<tr>");
      sb.append("<td>" + poolName + "</td>");
      for (String type : typeList) {
        Map<String, PoolMetrics> poolMetrics = scheduler.getPoolMetrics(type);
        PoolMetrics metric = poolMetrics.get(poolName);
        for (PoolMetrics.MetricName metricsName : PoolMetrics.MetricName.values()) {
          long val = metric.getCounter(metricsName);
          if (metricsName == PoolMetrics.MetricName.MAX && val == Integer.MAX_VALUE) {
            sb.append("<td>-</td>");
            continue;
          }
          sb.append("<td>" + val + "</td>");
        }
      }
      sb.append("</tr>\n");
    }
    sb.append("</table><br>");
    out.print(sb.toString());
  }

  private String generateTableHeaderCells(Collection<String> headers, int span) {
    StringBuilder sb = new StringBuilder();
    String joinFrag = "<th colspan=" + span + ">";
    sb.append(joinFrag);
    sb.append(org.apache.commons.lang.StringUtils.join(headers, "</th>" + joinFrag));
    sb.append("</th>");
    return sb.toString();
  }

  public void generateRetiredSessionTable(JspWriter out, NodeManager nm, SessionManager sm)
  throws IOException {

    StringBuilder sb = new StringBuilder();
    sb.append("<table border=\"1\" cellpadding=\"5\" cellspacing=\"0\">\n");

    // populate header row
    sb.append("<tr>");

    // fixed headers
    String [] fixedHeaders = {"Id", "User", "Name", "Status"};
    sb.append(generateTableHeaderCells(Arrays.asList(fixedHeaders), 1));

    // header per type
    List<String> resourceTypes = nm.getResourceTypes();
    sb.append(generateTableHeaderCells(resourceTypes, 1));
    sb.append("</tr>\n");

    Collection<RetiredSession> retiredSessions = sm.getRetiredSessions();
    synchronized(retiredSessions) {
      for(RetiredSession s: retiredSessions) {

        // populate row per retired session
        sb.append("<tr>");

        // fixed columns: Id/Url + Userid + Name + Status
        String url = "<a href=\"" + s.getUrl() + "\">" + s.sessionId + "</a>";
        sb.append("<td>" + url + "</td>");
        sb.append("<td>" + s.getUserId() + "</td>");
        sb.append("<td>" + s.getName() + "</td>");
        sb.append("<td>" + s.status + "</td>");

        // variable columns
        for (String resourceType: resourceTypes) {
          int total = s.getFulfilledRequestCountForType(resourceType);
          sb.append("<td>" + total + "</td>");
        }
        sb.append("</tr>\n");
      }
    }
    out.print(sb.toString());
    out.print("<br>");
  }%>

<%
  ClusterManager cm = (ClusterManager) application.getAttribute("cm");
  NodeManager nm = cm.getNodeManager();
  SessionManager sm = cm.getSessionManager();
  String cmHostName = StringUtils.simpleHostname(cm.getHostName());
%>

<html>
<head>
<title><%= cmHostName %> Corona Cluster Manager </title>
<link rel="stylesheet" type="text/css" href="/static/hadoop.css">
</head>
<body>

<div id="quicklinks">
  <a href="#quicklinks" onclick="toggle('quicklinks-list'); return false;">Quick Links</a>
  <ul id="quicklinks-list">
    <li><a href="#running_sessions">Running Jobs</a></li>
  </ul>
</div>

<h1><%= cmHostName %> Corona Cluster Manager </h1>
<b>Started:</b> <%= new Date(cm.getStartTime())%><br>
<hr>
<h2>Cluster Summary</h2>
<%
    generateSummaryTable(out, cm.getNodeManager(), cm.getSessionManager());
%>

<h2>Active Sessions</h2>
<%
    generateActiveSessionTable(out, cm.getNodeManager(), cm.getSessionManager());
%>

<h2>Pools</h2>
<%
    generatePoolTable(out, cm.getScheduler(), cm.getTypes());
%>

<h2>Retired Sessions</h2>
<%
    generateRetiredSessionTable(out, cm.getNodeManager(), cm.getSessionManager());
%>

<%
  out.println("</body></html>");
%>
