package com.valorant.tracker.model;
public record LiveStream(
        String platform,
        String id,
        String channelId,
        String channelTitle,
        String title,
        long viewers,
        String url
) {}