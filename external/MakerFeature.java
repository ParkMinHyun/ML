/*
 * Copyright (C) 2023 Samsung Electronics Co., Ltd. All rights reserved.
 *
 * IT & Mobile Communications,
 * Mobile Communications Business, Samsung Electronics Co., Ltd.
 *
 * This software and its documentation are confidential and proprietary
 * information of Samsung Electronics Co., Ltd.
 * No part of the software and documents may be copied, reproduced, transmitted,
 * translated, or reduced to any electronic medium or machine-readable form
 * without the prior written consent of Samsung Electronics.
 *
 * Samsung Electronics makes no representations with respect to the contents,
 * and assumes no responsibility for any errors that might appear in the software and
 * documents. This publication and the contents hereof are subject
 * to change without notice.
 */

package com.samsung.android.camera.core2.maker;

public class MakerFeature {

    private MakerFeature() {}

    public static final int AGIF_BURST_PICTURE_MAX_COUNT = 30;
    public static final int AGIF_IMAGE_WIDTH = 640;

    public static final int HIGH_SPEED_PREVIEW_FPS = 60;
    public static final int HIGH_SPEED_MIN_RECORDING_FPS = 100;

    public static final int RECORD_PRE_ALLOC_BUFFER_WIDTH = 2560;
    public static final int RECORD_PRE_ALLOC_WAIT_TIMEOUT_S = 5;

    public static final int PHOTO_HIGH_RES_MIN_WIDTH = 5472;
    public static final int PHOTO_HIGH_RES_MIN_HEIGHT = 6112; //When width is smaller than height. (ex.4472X6112, full ratio)

    public static final Long CAPTURE_TIMEOUT_MS = 7000L;
}
