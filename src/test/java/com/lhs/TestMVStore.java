/*
 * Copyright 2004-2022 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lhs;

import com.lhs.mvstore.MVMap;
import com.lhs.mvstore.MVStore;
import com.lhs.util.FileUtils;
import org.junit.Test;

/**
 * Tests the MVStore.
 */
public class TestMVStore {

    private static String MVDBFILE = "/Users/huashen/test15.mv.db";

    @Test
    public static void testOpenMVMap() {
        String fileName = MVDBFILE;
        FileUtils.delete(fileName);

        MVStore s = MVStore.open(fileName);
        MVMap map = s.openMap("data");
        for (int i = 0; i < 400; i++) {
            map.put(i + "", i + "");
        }
        s.commit();
        s.close();
    }

    @Test
    public static void testReadMVMap() {
        String fileName = MVDBFILE;
        MVStore s = MVStore.open(fileName);
        MVMap map = s.openMap("data");
        System.out.println(map.get("399"));
    }

    public static void main(String[] args) {
        testOpenMVMap();
        testReadMVMap();
    }
}
