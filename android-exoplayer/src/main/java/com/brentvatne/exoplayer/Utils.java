package com.brentvatne.exoplayer;

public class Utils {
    public static float[] getScaleAspectFit(int angle, int widthIn, int heightIn, int widthOut, int heightOut) {
        final float[] scale = {1, 1};
        scale[0] = scale[1] = 1;
        if (angle == 90 || angle == 270) {
            int cx = widthIn;
            widthIn = heightIn;
            heightIn = cx;
        }

        float aspectRatioIn = (float) widthIn / (float) heightIn;
        float heightOutCalculated = (float) widthOut / aspectRatioIn;

        if (heightOutCalculated < heightOut) {
            scale[1] = heightOutCalculated / heightOut;
        } else {
            scale[0] = heightOut * aspectRatioIn / widthOut;
        }

        return scale;
    }

    public static int calculateAngle(int angle) {
        switch (angle) {
            case -1:
                return -90;
            case 1:
                return 90;
            default:
                return angle;
        }
    }
}
