/* 
 * Copyright 2014 OpenMarket Ltd
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matrix.androidsdk.rest.api;

import org.matrix.androidsdk.rest.model.PowerLevels;
import org.matrix.androidsdk.rest.model.bingrules.BingRule;
import org.matrix.androidsdk.rest.model.bingrules.BingRulesResponse;

import retrofit.Callback;
import retrofit.http.Body;
import retrofit.http.DELETE;
import retrofit.http.GET;
import retrofit.http.PUT;
import retrofit.http.Path;

public interface BingRulesApi {

    @GET("/pushrules/")
    public void getAllBingRules(Callback<BingRulesResponse> callback);

    @GET("/pushrules/")
    public BingRulesResponse getAllBingRules();

    /**
     * Update the ruleID enable status
     * @param kind the notification kind (sender, room...)
     * @param ruleId the ruleId
     * @param enable the new enable status
     * @param callback the callback
     */
    @PUT("/pushrules/global/{kind}/{ruleId}/enabled")
    public void updateEnableRuleStatus(@Path("kind") String kind, @Path("ruleId") String ruleId, @Body Boolean enable, Callback<Void> callback);

    /**
     * Update the ruleID enable status
     * @param kind the notification kind (sender, room...)
     * @param ruleId the ruleId
     * @param callback the callback
     */
    @DELETE("/pushrules/global/{kind}/{ruleId}")
    public void deleteRule(@Path("kind") String kind, @Path("ruleId") String ruleId, Callback<Void> callback);

    /**
     * Add the ruleID enable status
     * @param kind the notification kind (sender, room...)
     * @param ruleId the ruleId.
     * @param rule the rule to add.
     * @param callback the callback.
     */
    @PUT("/pushrules/global/{kind}/{ruleId}")
    public void addRule(@Path("kind") String kind, @Path("ruleId") String ruleId, @Body BingRule rule, Callback<Void> callback);



}
