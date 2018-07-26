/*

  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package org.jenkinsci.plugins.testrail;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;

import org.jenkinsci.plugins.testrail.JunitResults.Testcase;
import org.jenkinsci.plugins.testrail.TestRailObjects.*;

import org.apache.commons.lang.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.xml.ws.http.HTTPException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.InterruptedException;
import java.util.Iterator;
import static org.jenkinsci.plugins.testrail.Utils.*;
/**
 * Created by Drew on 3/19/14.
 */
public class TestRailClient {
    private String host;
    private String user;
    private String password;

    public void setHost(String host) { this.host = host; }
    public void setUser(String user) { this.user = user; }
    public void setPassword(String password) {this.password = password; }
    public String getHost() { return this.host; }
    public String getUser() { return this.user; }
    public String getPassword() { return this.password; }

    public TestRailClient(String host, String user, String password) {
        this.host = host;
        this.user = user;
        this.password = password;
    }

    private HttpClient setUpHttpClient(HttpMethod method) {
        HttpClient httpclient = new HttpClient();
        httpclient.getParams().setAuthenticationPreemptive(true);
        httpclient.getState().setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(this.user, this.password)
        );
        method.setDoAuthentication(true);
        method.addRequestHeader("Content-Type", "application/json");
        return httpclient;
    }

    private TestRailResponse httpGet(String path) throws IOException {
        TestRailResponse response;

        do {
            response = httpGetInt(path);
            if (response.getStatus() == 429) {
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    log(e.toString());
                }
            }
       } while (response.getStatus() == 429);

       return response;
    }

    private TestRailResponse httpGetInt(String path) throws IOException {
        TestRailResponse result;
        GetMethod get = new GetMethod(host + "/" + path);
        HttpClient httpclient = setUpHttpClient(get);

        try {
            Integer status = httpclient.executeMethod(get);
            String body = new String(get.getResponseBody(), get.getResponseCharSet());
            result = new TestRailResponse(status, body);
        } finally {
            get.releaseConnection();
        }

        return result;
    }

    private TestRailResponse httpPost(String path, String payload)
        throws UnsupportedEncodingException, IOException, HTTPException, TestRailException {
        TestRailResponse response;

        do {
            response = httpPostInt(path, payload);
            if (response.getStatus() == 429) {
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    log(e.toString());
                }
            }
        } while (response.getStatus() == 429);

        if (response.getStatus() != 200) {
            // any status code other than 200 is an error
            throw new TestRailException("Posting to " + path + " returned an error! Response from TestRail is: \n" + response.getBody());
        }
        return response;
    }

    private TestRailResponse httpPostInt(String path, String payload)
            throws IOException, HTTPException {
        TestRailResponse result;
        PostMethod post = new PostMethod(host + "/" + path);
        HttpClient httpclient = setUpHttpClient(post);

        try {
            StringRequestEntity requestEntity = new StringRequestEntity(
                    payload,
                    "application/json",
                    "UTF-8"
            );
            post.setRequestEntity(requestEntity);
            Integer status = httpclient.executeMethod(post);
            String body = new String(post.getResponseBody(), post.getResponseCharSet());
            result = new TestRailResponse(status, body);
        } finally {
            post.releaseConnection();
        }

        return result;
    }

    public boolean serverReachable() throws IOException {
        boolean result = false;
        HttpClient httpclient = new HttpClient();
        GetMethod get = new GetMethod(host);
        try {
            httpclient.executeMethod(get);
            result = true;
        } catch (java.net.UnknownHostException e) {
            // nop - we default to result == false
        } finally {
            get.releaseConnection();
        }
        return result;
    }

    public boolean authenticationWorks() throws IOException {
        try {
            TestRailResponse response = httpGet("/index.php?/api/v2/get_projects");
            return (200 == response.getStatus());
        } catch (Exception e) {
            return false;
        }
    }

    public Project[] getProjects() throws IOException, ElementNotFoundException {
        String body = httpGet("/index.php?/api/v2/get_projects").getBody();
        JSONArray json;
        try { // testrail will return a single object rather than an array if there
              // is only one project
            json = new JSONArray(body);
        } catch (Exception e) {
            json = new JSONArray();
            json.put(new JSONObject(body));
        }
        Project[] projects = new Project[json.length()];
        for (int i = 0; i < json.length(); i++) {
            JSONObject o = json.getJSONObject(i);
            Project p = new Project();
            p.setName(o.getString("name"));
            p.setId(o.getInt("id"));
            projects[i] = p;
        }
        return projects;
    }

    public int getProjectId(String projectName) throws IOException, ElementNotFoundException {
        Project[] projects = getProjects();
        for (Project project : projects) {
            if (project.getName().equals(projectName)) {
                return project.getId();
            }
        }

        throw new ElementNotFoundException(projectName);
    }

    public Suite[] getSuites(int projectId) throws IOException, ElementNotFoundException {
        String body = httpGet("/index.php?/api/v2/get_suites/" + projectId).getBody();

        JSONArray json;
        try {
            json = new JSONArray(body);
        } catch (JSONException e) {
            return new Suite[0];
        }

        Suite[] suites = new Suite[json.length()];
        for (int i = 0; i < json.length(); i++) {
            JSONObject o = json.getJSONObject(i);
            Suite s = new Suite();
            s.setName(o.getString("name"));
            s.setId(o.getInt("id"));
            suites[i] = s;
        }

        return suites;
    }

    public Run[] getRuns(int projectId) throws IOException, ElementNotFoundException {
        String body = httpGet("index.php?/api/v2/get_runs/" + projectId).getBody();

        JSONArray json;

        try {
            json = new JSONArray(body);
        } catch (JSONException e) {
            throw new ElementNotFoundException("No runs for project " + projectId + "! Response from TestRail is: \n" + body);
        }

        Run[] runs = new Run[json.length()];
        for (int i = 0; i < json.length(); i++) {
            JSONObject o = json.getJSONObject(i);
            runs[i] = createRunFromJson(o);
        }

        return runs;
    }

    public String getCasesString(int projectId, int suiteId) {
        return "index.php?/api/v2/get_cases/" + projectId + "&suite_id=" + suiteId;
    }

    public Case[] getCases(int projectId, int suiteId) throws IOException, ElementNotFoundException {
        String body = httpGet(getCasesString(projectId, suiteId)).getBody();
        JSONArray json;

        try {
            json = new JSONArray(body);
        } catch (JSONException e) {
            throw new ElementNotFoundException("No cases for project " + projectId + " and suite " + suiteId + "! Response from TestRail is: \n" + body);
        }

        Case[] cases = new Case[json.length()];
        for (int i = 0; i < json.length(); i++) {
            JSONObject o = json.getJSONObject(i);
            cases[i] = createCaseFromJson(o);
        }

        return cases;
    }

    public Section[] getSections(int projectId, int suiteId) throws IOException, ElementNotFoundException {
        String body = httpGet("index.php?/api/v2/get_sections/" + projectId + "&suite_id=" + suiteId).getBody();
        JSONArray json = new JSONArray(body);

        Section[] sects = new Section[json.length()];
        for (int i = 0; i < json.length(); i++) {
            JSONObject o = json.getJSONObject(i);
            sects[i] = createSectionFromJSON(o);
        }

        return sects;
    }
    private Section createSectionFromJSON(JSONObject o) {
        Section s = new Section();

        s.setName(o.getString("name"));
        s.setId(o.getInt("id"));

        if (!o.isNull("parent_id")) {
            s.setParentId(String.valueOf(o.getInt("parent_id")));
        } else {
            s.setParentId("null");
        }

        s.setSuiteId(o.getInt("suite_id"));

        return s;
    }

    public Section addSection(String sectionName, int projectId, int suiteId, String parentId) 
            throws IOException, ElementNotFoundException, TestRailException {
        String payload = new JSONObject().put("name", sectionName).put("suite_id", suiteId).put("parent_id", parentId).toString();
        String body = httpPost("index.php?/api/v2/add_section/" + projectId , payload).getBody();
        JSONObject o = new JSONObject(body);

        return createSectionFromJSON(o);
    }

    private Case createCaseFromJson(JSONObject o) {
        Case s = new Case();
        
        s.setTitle(o.getString("title"));
        s.setId(o.getInt("id"));
        s.setSectionId(o.getInt("section_id"));
        s.setRefs(o.optString("refs"));

        return s;
    }

    private Run createRunFromJson(JSONObject o) {
        Run r = new Run();

        r.setId(o.getInt("id") + "");
        r.setSuiteId(o.getInt("suite_id") + "");
        r.setName(o.getString("name"));
        if (!o.isNull("milestone_id")) {
            r.setMilestoneId(o.getInt("milestone_id") + "");
        }

        return r;
    }

    public Case addCase(Testcase caseToAdd, int sectionId) 
            throws IOException, TestRailException {
        JSONObject payload = new JSONObject().put("title", caseToAdd.getName());
        if (!StringUtils.isEmpty(caseToAdd.getRefs())) {
            payload.put("refs", caseToAdd.getRefs());
        }

        String body = httpPost("index.php?/api/v2/add_case/" + sectionId, payload.toString()).getBody();
        Case caseFromJson = createCaseFromJson(new JSONObject(body));
        return caseFromJson;
    }

    public TestRailResponse addResultsForCases(int runId, Results results, String extraParameters) 
            throws IOException, TestRailException {
        JSONArray a = new JSONArray();
        for (int i = 0; i < results.getResults().size(); i++) {
            JSONObject o = new JSONObject();
            Result r = results.getResults().get(i);
            o.put("case_id", r.getCaseId()).put("status_id", r.getStatus().getValue()).put("comment", r.getComment()).put("elapsed", r.getElapsedTimeString());

            if (extraParameters.length() > 0) {
                JSONObject xp = new JSONObject(extraParameters);
                Iterator<String> keys = xp.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    o.put(k, xp.get(k).toString());
                }
            }

            a.put(o);
        }

        String payload = new JSONObject().put("results", a).toString();
        log(payload);
        TestRailResponse response = httpPost("index.php?/api/v2/add_results_for_cases/" + runId, payload);
        return response;
    }

    public int addRun(int projectId, int suiteId, String milestoneID, String description)
            throws IOException, TestRailException {
        String payload = new JSONObject().put("suite_id", suiteId).put("description", description).put("milestone_id", milestoneID).toString();
        String body = httpPost("index.php?/api/v2/add_run/" + projectId, payload).getBody();
        return new JSONObject(body).getInt("id");
    }

    public Milestone[] getMilestones(int projectId) throws IOException, ElementNotFoundException {
        String body = httpGet("index.php?/api/v2/get_milestones/" + projectId).getBody();
        JSONArray json;
        try {
          json = new JSONArray(body);
        } catch (JSONException e) {
            return new Milestone[0];
        }
        Milestone[] suites = new Milestone[json.length()];
        for (int i = 0; i < json.length(); i++) {
            JSONObject o = json.getJSONObject(i);
            Milestone s = new Milestone();
            s.setName(o.getString("name"));
            s.setId(String.valueOf(o.getInt("id")));
            suites[i] = s;
        }
        return suites;
    }

    public String getMilestoneID(String milestoneName, int projectId) throws IOException, ElementNotFoundException {
      for (Milestone milestone: getMilestones(projectId)) {
         if (milestone.getName().equals(milestoneName)) {
             return milestone.getId();
         }
      }
      throw new ElementNotFoundException("Milestone id not found.");
    }

    public String getMilestoneName(String milestoneId, int projectId) throws IOException, ElementNotFoundException {
      for (Milestone milestone: getMilestones(projectId)) {
        if (milestone.getId() == milestoneId) {
          return milestone.getName();
        }
      }
      throw new ElementNotFoundException("Milestone " + milestoneId + " not found in Project " + projectId);
    }

    public boolean closeRun(int runId)
            throws IOException, TestRailException {
        String payload = "";
        int status = httpPost("index.php?/api/v2/close_run/" + runId, payload).getStatus();
        return (200 == status);
    }
}
