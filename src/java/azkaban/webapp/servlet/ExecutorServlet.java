/*
 * Copyright 2012 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.webapp.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import azkaban.executor.ExecutableFlow;
import azkaban.executor.ExecutableNode;
import azkaban.executor.ExecutionOptions;
import azkaban.executor.ExecutionOptions.FailureAction;
import azkaban.executor.ExecutorManager;
import azkaban.executor.ExecutorManagerException;
import azkaban.executor.Status;
import azkaban.flow.Flow;
import azkaban.project.Project;
import azkaban.project.ProjectManager;
import azkaban.scheduler.Schedule;
import azkaban.scheduler.ScheduleManager;
import azkaban.user.Permission;
import azkaban.user.User;
import azkaban.user.Permission.Type;
import azkaban.utils.FileIOUtils.JobMetaData;
import azkaban.utils.FileIOUtils.LogData;
import azkaban.webapp.AzkabanWebServer;
import azkaban.webapp.session.Session;

public class ExecutorServlet extends LoginAbstractAzkabanServlet {
	private static final long serialVersionUID = 1L;
	private ProjectManager projectManager;
	private ExecutorManager executorManager;
	private ScheduleManager scheduleManager;
	private ExecutorVelocityHelper velocityHelper;

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		AzkabanWebServer server = (AzkabanWebServer)getApplication();
		projectManager = server.getProjectManager();
		executorManager = server.getExecutorManager();
		scheduleManager = server.getScheduleManager();
		velocityHelper = new ExecutorVelocityHelper();
	}

	@Override
	protected void handleGet(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		if (hasParam(req, "ajax")) {
			handleAJAXAction(req, resp, session);
		}
		else if (hasParam(req, "execid")) {
			if (hasParam(req, "job")) {
				handleExecutionJobPage(req, resp, session);
			}
			else {
				handleExecutionFlowPage(req, resp, session);
			}
		}
		else {
			handleExecutionsPage(req, resp, session);
		}
	}
	
	private void handleExecutionJobPage(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		Page page = newPage(req, resp, session, "azkaban/webapp/servlet/velocity/joblogpage.vm");
		User user = session.getUser();
		int execId = getIntParam(req, "execid");
		String jobId = getParam(req, "job");
		int attempt = getIntParam(req, "attempt", 0);
		page.add("execid", execId);
		page.add("jobid", jobId);
		page.add("attempt", attempt);
		
		ExecutableFlow flow = null;
		try {
			flow = executorManager.getExecutableFlow(execId);
			if (flow == null) {
				page.add("errorMsg", "Error loading executing flow " + execId + " not found.");
				page.render();
				return;
			}
		} catch (ExecutorManagerException e) {
			page.add("errorMsg", "Error loading executing flow: " + e.getMessage());
			page.render();
			return;
		}
		
		int projectId = flow.getProjectId();
		Project project = getProjectPageByPermission(page, projectId, user, Type.READ);
		if (project == null) {
			page.render();
			return;
		}
		
		page.add("projectName", project.getName());
		page.add("flowid", flow.getFlowId());
		
		page.render();
	}
	
	private void handleExecutionsPage(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		Page page = newPage(req, resp, session, "azkaban/webapp/servlet/velocity/executionspage.vm");

		List<ExecutableFlow> runningFlows = executorManager.getRunningFlows();
		page.add("runningFlows", runningFlows.isEmpty() ? null : runningFlows);
		
		List<ExecutableFlow> finishedFlows = executorManager.getRecentlyFinishedFlows();
		page.add("recentlyFinished", finishedFlows.isEmpty() ? null : finishedFlows);
		page.add("vmutils", velocityHelper);
		page.render();
	}
	
	private void handleExecutionFlowPage(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		Page page = newPage(req, resp, session, "azkaban/webapp/servlet/velocity/executingflowpage.vm");
		User user = session.getUser();
		int execId = getIntParam(req, "execid");
		page.add("execid", execId);

		ExecutableFlow flow = null;
		try {
			flow = executorManager.getExecutableFlow(execId);
			if (flow == null) {
				page.add("errorMsg", "Error loading executing flow " + execId + " not found.");
				page.render();
				return;
			}
		} catch (ExecutorManagerException e) {
			page.add("errorMsg", "Error loading executing flow: " + e.getMessage());
			page.render();
			return;
		}
		
		int projectId = flow.getProjectId();
		Project project = getProjectPageByPermission(page, projectId, user, Type.READ);
		if(project == null) {
			page.render();
			return;
		}
		
		page.add("projectId", project.getId());
		page.add("projectName", project.getName());
		page.add("flowid", flow.getFlowId());
		
		page.render();
	}
	
	protected Project getProjectPageByPermission(Page page, int projectId, User user, Permission.Type type) {
		Project project = projectManager.getProject(projectId);
		
		if (project == null) {
			page.add("errorMsg", "Project " + project + " not found.");
		}
		else if (!hasPermission(project, user, type)) {
			page.add("errorMsg", "User " + user.getUserId() + " doesn't have " + type.name() + " permissions on " + project.getName());
		}
		else {
			return project;
		}
		
		return null;
	}

	protected Project getProjectAjaxByPermission(Map<String, Object> ret, String projectName, User user, Permission.Type type) {
		Project project = projectManager.getProject(projectName);
		
		if (project == null) {
			ret.put("error", "Project '" + project + "' not found.");
		}
		else if (!hasPermission(project, user, type)) {
			ret.put("error", "User '" + user.getUserId() + "' doesn't have " + type.name() + " permissions on " + project.getName());
		}
		else {
			return project;
		}
		
		return null;
	}
	
	protected Project getProjectAjaxByPermission(Map<String, Object> ret, int projectId, User user, Permission.Type type) {
		Project project = projectManager.getProject(projectId);
		
		if (project == null) {
			ret.put("error", "Project '" + project + "' not found.");
		}
		else if (!hasPermission(project, user, type)) {
			ret.put("error", "User '" + user.getUserId() + "' doesn't have " + type.name() + " permissions on " + project.getName());
		}
		else {
			return project;
		}
		
		return null;
	}
	
	@Override
	protected void handlePost(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		if (hasParam(req, "ajax")) {
			handleAJAXAction(req, resp, session);
		}
	}

	private void handleAJAXAction(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		HashMap<String, Object> ret = new HashMap<String, Object>();
		String ajaxName = getParam(req, "ajax");
		
		if (hasParam(req, "execid")) {
			int execid = getIntParam(req, "execid");
			ExecutableFlow exFlow = null;

			try {
				exFlow = executorManager.getExecutableFlow(execid);
			} catch (ExecutorManagerException e) {
				ret.put("error", "Error fetching execution '" + execid + "': " + e.getMessage());
			}

			if (exFlow == null) {
				ret.put("error", "Cannot find execution '" + execid + "'");
			}
			else {
				if (ajaxName.equals("fetchexecflow")) {
					ajaxFetchExecutableFlow(req, resp, ret, session.getUser(), exFlow);
				}
				else if (ajaxName.equals("fetchexecflowupdate")) {
					ajaxFetchExecutableFlowUpdate(req, resp, ret, session.getUser(), exFlow);
				}
				else if (ajaxName.equals("cancelFlow")) {
					ajaxCancelFlow(req, resp, ret, session.getUser(), exFlow);
				}
				else if (ajaxName.equals("restartFlow")) {
					ajaxRestartFlow(req, resp, ret, session.getUser(), exFlow);
				}
				else if (ajaxName.equals("pauseFlow")) {
					ajaxPauseFlow(req, resp, ret, session.getUser(), exFlow);
				}
				else if (ajaxName.equals("resumeFlow")) {
					ajaxResumeFlow(req, resp, ret, session.getUser(), exFlow);
				}
				else if (ajaxName.equals("fetchExecFlowLogs")) {
					ajaxFetchExecFlowLogs(req, resp, ret, session.getUser(), exFlow);
				}
				else if (ajaxName.equals("fetchExecJobLogs")) {
					ajaxFetchJobLogs(req, resp, ret, session.getUser(), exFlow);
				}
				else if (ajaxName.equals("retryFailedJobs")) {
					ajaxRestartFailed(req, resp, ret, session.getUser(), exFlow);
				}
//				else if (ajaxName.equals("fetchLatestJobStatus")) {
//					ajaxFetchLatestJobStatus(req, resp, ret, session.getUser(), exFlow);
//				}
				else if (ajaxName.equals("flowInfo")) {
					//String projectName = getParam(req, "project");
					//Project project = projectManager.getProject(projectName);
					//String flowName = getParam(req, "flow");
					ajaxFetchExecutableFlowInfo(req, resp, ret, session.getUser(), exFlow);
				}
			}
		}
		else if (ajaxName.equals("getRunning")) {
			String projectName = getParam(req, "project");
			String flowName = getParam(req, "flow");
			ajaxGetFlowRunning(req, resp, ret, session.getUser(), projectName, flowName);
		}
		else if (ajaxName.equals("flowInfo")) {
			String projectName = getParam(req, "project");
			String flowName = getParam(req, "flow");
			ajaxFetchFlowInfo(req, resp, ret, session.getUser(), projectName, flowName);
		}
		else {
			String projectName = getParam(req, "project");
			
			ret.put("project", projectName);
			if (ajaxName.equals("executeFlow")) {
				ajaxAttemptExecuteFlow(req, resp, ret, session.getUser());
			}
		}
		if (ret != null) {
			this.writeJSON(resp, ret);
		}
	}

//	private void ajaxFetchLatestJobStatus(HttpServletRequest req,HttpServletResponse resp, HashMap<String, Object> ret, User user, ExecutableFlow exFlow) {
//		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.READ);
//		if (project == null) {
//			ret.put("error", "Project doesn't exist or incorrect access permission.");
//			return;
//		}
//		
//		String projectName;
//		String flowName;
//		String jobName;
//		try {
//			projectName = getParam(req, "projectName");
//			flowName = getParam(req, "flowName");
//			jobName = getParam(req, "jobName");
//		} catch (Exception e) {
//			ret.put("error", e.getMessage());
//			return;
//		}
//		
//		try {
//			ExecutableNode node = exFlow.getExecutableNode(jobId);
//			if (node == null) {
//				ret.put("error", "Job " + jobId + " doesn't exist in " + exFlow.getExecutionId());
//				return;
//			}
//			
//			int attempt = this.getIntParam(req, "attempt", node.getAttempt());
//			LogData data = executorManager.getExecutionJobLog(exFlow, jobId, offset, length, attempt);
//			if (data == null) {
//				ret.put("length", 0);
//				ret.put("offset", offset);
//				ret.put("data", "");
//			}
//			else {
//				ret.put("length", data.getLength());
//				ret.put("offset", data.getOffset());
//				ret.put("data", data.getData());
//			}
//		} catch (ExecutorManagerException e) {
//			throw new ServletException(e);
//		}
//		
//	}

	private void ajaxRestartFailed(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, ExecutableFlow exFlow) throws ServletException {
		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.EXECUTE);
		if (project == null) {
			return;
		}
		
		if (exFlow.getStatus() == Status.FAILED || exFlow.getStatus() == Status.SUCCEEDED) {
			ret.put("error", "Flow has already finished. Please re-execute.");
			return;
		}
		
		try {
			executorManager.retryFailures(exFlow, user.getUserId());
		} catch (ExecutorManagerException e) {
			ret.put("error", e.getMessage());
		}
	}
	
	/**
	 * Gets the logs through plain text stream to reduce memory overhead.
	 * 
	 * @param req
	 * @param resp
	 * @param user
	 * @param exFlow
	 * @throws ServletException
	 */
	private void ajaxFetchExecFlowLogs(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, ExecutableFlow exFlow) throws ServletException {
		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.READ);
		if (project == null) {
			return;
		}
		
		int offset = this.getIntParam(req, "offset");
		int length = this.getIntParam(req, "length");
		
		resp.setCharacterEncoding("utf-8");

		try {
			LogData data = executorManager.getExecutableFlowLog(exFlow, offset, length);
			if (data == null) {
				ret.put("length", 0);
				ret.put("offset", offset);
				ret.put("data", "");
			}
			else {
				ret.put("length", data.getLength());
				ret.put("offset", data.getOffset());
				ret.put("data", data.getData());
			}
		} catch (ExecutorManagerException e) {
			throw new ServletException(e);
		}
	}
	
	/**
	 * Gets the logs through ajax plain text stream to reduce memory overhead.
	 * 
	 * @param req
	 * @param resp
	 * @param user
	 * @param exFlow
	 * @throws ServletException
	 */
	private void ajaxFetchJobLogs(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, ExecutableFlow exFlow) throws ServletException {
		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.READ);
		if (project == null) {
			return;
		}
		
		int offset = this.getIntParam(req, "offset");
		int length = this.getIntParam(req, "length");
		
		String jobId = this.getParam(req, "jobId");
		resp.setCharacterEncoding("utf-8");

		try {
			ExecutableNode node = exFlow.getExecutableNode(jobId);
			if (node == null) {
				ret.put("error", "Job " + jobId + " doesn't exist in " + exFlow.getExecutionId());
				return;
			}
			
			int attempt = this.getIntParam(req, "attempt", node.getAttempt());
			LogData data = executorManager.getExecutionJobLog(exFlow, jobId, offset, length, attempt);
			if (data == null) {
				ret.put("length", 0);
				ret.put("offset", offset);
				ret.put("data", "");
			}
			else {
				ret.put("length", data.getLength());
				ret.put("offset", data.getOffset());
				ret.put("data", data.getData());
			}
		} catch (ExecutorManagerException e) {
			throw new ServletException(e);
		}
	}

	/**
	 * Gets the job metadata through ajax plain text stream to reduce memory overhead.
	 * 
	 * @param req
	 * @param resp
	 * @param user
	 * @param exFlow
	 * @throws ServletException
	 */
	private void ajaxFetchJobMetaData(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, ExecutableFlow exFlow) throws ServletException {
		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.READ);
		if (project == null) {
			return;
		}
		
		int offset = this.getIntParam(req, "offset");
		int length = this.getIntParam(req, "length");
		
		String jobId = this.getParam(req, "jobId");
		resp.setCharacterEncoding("utf-8");

		try {
			ExecutableNode node = exFlow.getExecutableNode(jobId);
			if (node == null) {
				ret.put("error", "Job " + jobId + " doesn't exist in " + exFlow.getExecutionId());
				return;
			}
			
			int attempt = this.getIntParam(req, "attempt", node.getAttempt());
			JobMetaData data = executorManager.getExecutionJobMetaData(exFlow, jobId, offset, length, attempt);
			if (data == null) {
				ret.put("length", 0);
				ret.put("offset", offset);
				ret.put("data", "");
			}
			else {
				ret.put("length", data.getLength());
				ret.put("offset", data.getOffset());
				ret.put("data", data.getData());
			}
		} catch (ExecutorManagerException e) {
			throw new ServletException(e);
		}
	}
	
	private void ajaxFetchFlowInfo(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, String projectName, String flowId) throws ServletException {
		Project project = getProjectAjaxByPermission(ret, projectName, user, Type.READ);
		if (project == null) {
			return;
		}
		
		Flow flow = project.getFlow(flowId);
		if (flow == null) {
			ret.put("error", "Error loading flow. Flow " + flowId + " doesn't exist in " + projectName);
			return;
		}
		
		ret.put("successEmails", flow.getSuccessEmails());
		ret.put("failureEmails", flow.getFailureEmails());
		
		Schedule sflow = null;
		for (Schedule sched: scheduleManager.getSchedules()) {
			if (sched.getProjectId() == project.getId() && sched.getFlowName().equals(flowId)) {
				sflow = sched;
				break;
			}
		}
		
		if (sflow != null) {
			ret.put("scheduled", sflow.getNextExecTime());
		}
	}

	private void ajaxFetchExecutableFlowInfo(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, ExecutableFlow exflow) throws ServletException {
		Project project = getProjectAjaxByPermission(ret, exflow.getProjectId(), user, Type.READ);
		if (project == null) {
			return;
		}
		
		Flow flow = project.getFlow(exflow.getFlowId());
		if (flow == null) {
			ret.put("error", "Error loading flow. Flow " + exflow.getFlowId() + " doesn't exist in " + exflow.getProjectId());
			return;
		}
		
		ExecutionOptions options = exflow.getExecutionOptions();
		
		ret.put("successEmails", options.getSuccessEmails());
		ret.put("failureEmails", options.getFailureEmails());
		ret.put("flowParam", options.getFlowParameters());
		
		FailureAction action = options.getFailureAction();
		String failureAction = null;
		switch (action) {
			case FINISH_CURRENTLY_RUNNING:
				failureAction = "finishCurrent";
				break;
			case CANCEL_ALL:
				failureAction = "cancelImmediately";
				break;
			case FINISH_ALL_POSSIBLE:
				failureAction = "finishPossible";
				break;
		}
		ret.put("failureAction", failureAction);
		
		ret.put("notifyFailureFirst", options.getNotifyOnFirstFailure());
		ret.put("notifyFailureLast", options.getNotifyOnLastFailure());
		
		ret.put("failureEmailsOverride", options.isFailureEmailsOverridden());
		ret.put("successEmailsOverride", options.isSuccessEmailsOverridden());
		
		ret.put("concurrentOptions", options.getConcurrentOption());
		ret.put("pipelineLevel", options.getPipelineLevel());
		ret.put("pipelineExecution", options.getPipelineExecutionId());
		ret.put("queueLevel", options.getQueueLevel());
		
		HashMap<String, String> nodeStatus = new HashMap<String,String>();
		for(ExecutableNode node : exflow.getExecutableNodes()) {
			nodeStatus.put(node.getJobId(), node.getStatus().toString());
		}
		ret.put("nodeStatus", nodeStatus);
		ret.put("disabled", options.getDisabledJobs());
	}
	
	private void ajaxCancelFlow(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, ExecutableFlow exFlow) throws ServletException{
		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.EXECUTE);
		if (project == null) {
			return;
		}
		
		try {
			executorManager.cancelFlow(exFlow, user.getUserId());
		} catch (ExecutorManagerException e) {
			ret.put("error", e.getMessage());
		}
	}

	private void ajaxGetFlowRunning(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, String projectId, String flowId) throws ServletException{
		Project project = getProjectAjaxByPermission(ret, projectId, user, Type.EXECUTE);
		if (project == null) {
			return;
		}
		
		List<Integer> refs = executorManager.getRunningFlows(project.getId(), flowId);
		if (!refs.isEmpty()) {
			ret.put("execIds", refs);
		}
	}
	
	private void ajaxRestartFlow(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, ExecutableFlow exFlow) throws ServletException{
		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.EXECUTE);
		if (project == null) {
			return;
		}
	}

	private void ajaxPauseFlow(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, ExecutableFlow exFlow) throws ServletException{
		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.EXECUTE);
		if (project == null) {
			return;
		}

		try {
			executorManager.pauseFlow(exFlow, user.getUserId());
		} catch (ExecutorManagerException e) {
			ret.put("error", e.getMessage());
		}
	}

	private void ajaxResumeFlow(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, ExecutableFlow exFlow) throws ServletException{
		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.EXECUTE);
		if (project == null) {
			return;
		}

		try {
			executorManager.resumeFlow(exFlow, user.getUserId());
		} catch (ExecutorManagerException e) {
			ret.put("resume", e.getMessage());
		}
	}
	
	private void ajaxFetchExecutableFlowUpdate(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, ExecutableFlow exFlow) throws ServletException{
		Long lastUpdateTime = Long.parseLong(getParam(req, "lastUpdateTime"));
		System.out.println("Fetching " + exFlow.getExecutionId());
		
		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.READ);
		if (project == null) {
			return;
		}

		// Just update the nodes and flow states
		ArrayList<Map<String, Object>> nodeList = new ArrayList<Map<String, Object>>();
		for (ExecutableNode node : exFlow.getExecutableNodes()) {
			if (node.getUpdateTime() <= lastUpdateTime) {
				continue;
			}
			
			HashMap<String, Object> nodeObj = new HashMap<String,Object>();
			nodeObj.put("id", node.getJobId());
			nodeObj.put("status", node.getStatus());
			nodeObj.put("startTime", node.getStartTime());
			nodeObj.put("endTime", node.getEndTime());
			nodeObj.put("attempt", node.getAttempt());
			
			if (node.getAttempt() > 0) {
				nodeObj.put("pastAttempts", node.getAttemptObjects());
			}
			
			nodeList.add(nodeObj);
		}

		ret.put("nodes", nodeList);
		ret.put("status", exFlow.getStatus().toString());
		ret.put("startTime", exFlow.getStartTime());
		ret.put("endTime", exFlow.getEndTime());
		ret.put("submitTime", exFlow.getSubmitTime());
		ret.put("updateTime", exFlow.getUpdateTime());
	}
	
	private void ajaxFetchExecutableFlow(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user, ExecutableFlow exFlow) throws ServletException {
		System.out.println("Fetching " + exFlow.getExecutionId());

		Project project = getProjectAjaxByPermission(ret, exFlow.getProjectId(), user, Type.READ);
		if (project == null) {
			return;
		}
		
		ArrayList<Map<String, Object>> nodeList = new ArrayList<Map<String, Object>>();
		ArrayList<Map<String, Object>> edgeList = new ArrayList<Map<String,Object>>();
		for (ExecutableNode node : exFlow.getExecutableNodes()) {
			HashMap<String, Object> nodeObj = new HashMap<String,Object>();
			nodeObj.put("id", node.getJobId());
			nodeObj.put("level", node.getLevel());
			nodeObj.put("status", node.getStatus());
			nodeObj.put("startTime", node.getStartTime());
			nodeObj.put("endTime", node.getEndTime());
			
			// Add past attempts
			if (node.getPastAttemptList() != null) {
				ArrayList<Object> pastAttempts = new ArrayList<Object>();
				for (ExecutableNode.Attempt attempt: node.getPastAttemptList()) {
					pastAttempts.add(attempt.toObject());
				}
				nodeObj.put("pastAttempts", pastAttempts);
			}
			
			nodeList.add(nodeObj);
			
			// Add edges
			for (String out: node.getOutNodes()) {
				HashMap<String, Object> edgeObj = new HashMap<String,Object>();
				edgeObj.put("from", node.getJobId());
				edgeObj.put("target", out);
				edgeList.add(edgeObj);
			}
		}

		ret.put("nodes", nodeList);
		ret.put("edges", edgeList);
		ret.put("status", exFlow.getStatus().toString());
		ret.put("startTime", exFlow.getStartTime());
		ret.put("endTime", exFlow.getEndTime());
		ret.put("submitTime", exFlow.getSubmitTime());
		ret.put("submitUser", exFlow.getSubmitUser());
	}
	
	private void ajaxAttemptExecuteFlow(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user) throws ServletException {
		String projectName = getParam(req, "project");
		String flowId = getParam(req, "flow");
		
		Project project = getProjectAjaxByPermission(ret, projectName, user, Type.EXECUTE);
		if (project == null) {
			ret.put("error", "Project '" + projectName + "' doesn't exist.");
			return;
		}
		
		ret.put("flow",  flowId);
		Flow flow = project.getFlow(flowId);
		if (flow == null) {
			ret.put("error", "Flow '" + flowId + "' cannot be found in project " + project);
			return;
		}
		
		ajaxExecuteFlow(req, resp, ret, user);
	}
	
	private void ajaxExecuteFlow(HttpServletRequest req, HttpServletResponse resp, HashMap<String, Object> ret, User user) throws ServletException {
		String projectName = getParam(req, "project");
		String flowId = getParam(req, "flow");
		
		Project project = getProjectAjaxByPermission(ret, projectName, user, Type.EXECUTE);
		if (project == null) {
			ret.put("error", "Project '" + projectName + "' doesn't exist.");
			return;
		}
		
		ret.put("flow",  flowId);
		Flow flow = project.getFlow(flowId);
		if (flow == null) {
			ret.put("error", "Flow '" + flowId + "' cannot be found in project " + project);
			return;
		}
		
		ExecutableFlow exflow = new ExecutableFlow(flow);
		exflow.setSubmitUser(user.getUserId());
		exflow.addAllProxyUsers(project.getProxyUsers());

		ExecutionOptions options = HttpRequestUtils.parseFlowOptions(req);
		exflow.setExecutionOptions(options);
		if (!options.isFailureEmailsOverridden()) {
			options.setFailureEmails(flow.getFailureEmails());
		}
		if (!options.isSuccessEmailsOverridden()) {
			options.setSuccessEmails(flow.getSuccessEmails());
		}
		
		try {
			String message = executorManager.submitExecutableFlow(exflow);
			ret.put("message", message);
		}
		catch (ExecutorManagerException e) {
			e.printStackTrace();
			ret.put("error", "Error submitting flow " + exflow.getFlowId() + ". " + e.getMessage());
		}

		ret.put("execid", exflow.getExecutionId());
	}
	
	public class ExecutorVelocityHelper {
		public String getProjectName(int id) {
			Project project = projectManager.getProject(id);
			if (project == null) {
				return String.valueOf(id);
			}
			
			return project.getName();
		}
	}
}
