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
package org.matrix.androidsdk.rest.callback;

import org.matrix.androidsdk.rest.model.MatrixError;

/**
 * Callback interface for asynchronously returning API call failures.
 */
public interface ApiFailureCallback {

    /**
     * Called if there is a network error.
     * @param e the exception
     */
    public void onNetworkError(Exception e);

    /**
     * Called in case of a Matrix error.
     * @param e the Matrix error
     */
    public void onMatrixError(MatrixError e);

    /**
     * Called for some other type of error.
     * @param e the exception
     */
    public void onUnexpectedError(Exception e);
}
