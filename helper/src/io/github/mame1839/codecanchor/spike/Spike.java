package io.github.mame1839.codecanchor.spike;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 実証の切り分けに使う対照経路。製品には入らない。
 *
 * AudioEffect(UUID, AudioDeviceAttributes) が動的な device effect の唯一の入口で、
 * AudioManager / AudioSystem に addDeviceEffect 相当は存在しない。
 */
public final class Spike {
    // 実装 UUID。AudioEffect の第 1 引数は type ではなく実装 UUID。
    static final UUID IMPL = UUID.fromString("7a1c9f60-4a2e-4f6b-9d21-0a5c1b3e77d1");
    static final int ROLE_OUTPUT = 2;                  // AudioDeviceAttributes.ROLE_OUTPUT
    static final int TYPE_BLUETOOTH_A2DP = 8;          // AudioDeviceInfo.TYPE_BLUETOOTH_A2DP

    // ---- 安全装置の定数。ここを緩めない -------------------------------------
    // この調査で、引数の取り違えにより意図より 20 dB 大きいテストトーンを
    // 装着中のイヤホンに流す事故が起きている。
    static final double MAX_DBFS = -20.0;   // これより大きい信号は出さない
    static final double MIN_DBFS = -60.0;
    static final float  TRACK_VOLUME = 0.25f;   // さらに -12 dB
    static final int    MAX_GAIN_MB = 0;        // 正のゲインは受け付けない
    static final int    MIN_GAIN_MB = -6000;

    static Object deviceAttrs(String mac) throws Exception {
        Class<?> ada = Class.forName("android.media.AudioDeviceAttributes");
        Constructor<?> ctor = ada.getDeclaredConstructor(int.class, int.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(ROLE_OUTPUT, TYPE_BLUETOOTH_A2DP, mac);
    }

    static AudioEffect createDeviceEffect(String mac) throws Exception {
        Class<?> ada = Class.forName("android.media.AudioDeviceAttributes");
        Constructor<?> ctor = AudioEffect.class.getDeclaredConstructor(UUID.class, ada);
        ctor.setAccessible(true);
        return (AudioEffect) ctor.newInstance(IMPL, deviceAttrs(mac));
    }

    static byte[] intToLe(int x) {
        return new byte[] { (byte) x, (byte) (x >> 8), (byte) (x >> 16), (byte) (x >> 24) };
    }

    // ---- 公開 android.jar に無いもの。@hide / @SystemApi なのでリフレクションで取る ----

    static int setParam(AudioEffect fx, byte[] p, byte[] v) throws Exception {
        Method m = AudioEffect.class.getDeclaredMethod("setParameter", byte[].class, byte[].class);
        m.setAccessible(true);
        return (Integer) m.invoke(fx, p, v);
    }

    static Object isSupportedForDevice(String mac) throws Exception {
        Class<?> ada = Class.forName("android.media.AudioDeviceAttributes");
        Method m = AudioEffect.class.getDeclaredMethod("isEffectSupportedForDevice", UUID.class, ada);
        m.setAccessible(true);
        return m.invoke(null, IMPL, deviceAttrs(mac));
    }

    static UUID effectTypeNull() throws Exception {
        Field f = AudioEffect.class.getDeclaredField("EFFECT_TYPE_NULL");
        f.setAccessible(true);
        return (UUID) f.get(null);
    }

    static void hold(String mac, int gainMb, int seconds) throws Exception {
        AudioEffect fx = createDeviceEffect(mac);
        System.out.println("生成した: " + fx.getDescriptor().name);
        System.out.println("hasControl=" + fx.hasControl());
        // 安全装置 1/3: 正のゲインは .so 側でも 0 に丸められるが、ここでも通さない。
        if (gainMb > MAX_GAIN_MB) gainMb = MAX_GAIN_MB;
        if (gainMb < MIN_GAIN_MB) gainMb = MIN_GAIN_MB;
        byte[] p = intToLe(1);          // param id 1 = ゲイン (millibel)
        byte[] v = intToLe(gainMb);
        int sp = setParam(fx, p, v);
        System.out.println("setParameter=" + sp + " gain=" + gainMb + "mB");
        int se = fx.setEnabled(true);
        System.out.println("setEnabled=" + se + " enabled=" + fx.getEnabled());
        System.out.println("--- ここまでの戻り値は何も証明しない。カウンタを見ること ---");
        // ハンドルを握り続ける。プロセスが死ねばエフェクトも解放される (参照カウント駆動)。
        for (int i = 0; i < seconds; i++) {
            Thread.sleep(1000);
            if (!fx.hasControl()) System.out.println("制御権を奪われた (t=" + i + "s)");
        }
        fx.setEnabled(false);
        fx.release();
        System.out.println("解放した");
    }

    static void tone(int hz, int seconds, double dbfs) throws Exception {
        // 安全装置 2/3: -20 dBFS より大きい信号は出さない。
        if (dbfs > MAX_DBFS) {
            System.out.println("dBFS が " + MAX_DBFS + " を超えている。丸める (要求値=" + dbfs + ")");
            dbfs = MAX_DBFS;
        }
        if (dbfs < MIN_DBFS) dbfs = MIN_DBFS;
        final int rate = 48000;
        final double amp = Math.pow(10.0, dbfs / 20.0);
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        // バッファを大きく取るのは FAST スレッドに載せないため。
        // FAST に載ると SW effect が届かず、実証の対象にならない。
        int buf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                                              AudioFormat.ENCODING_PCM_FLOAT) * 8;
        AudioTrack t = new AudioTrack.Builder().setAudioFormat(fmt).setAudioAttributes(attrs)
                .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM).build();
        t.setVolume(TRACK_VOLUME);   // 安全装置 3/3
        t.play();
        System.out.println("トーン開始 " + hz + "Hz " + dbfs + "dBFS vol=" + TRACK_VOLUME
                + " (振幅=" + amp + ")");
        float[] chunk = new float[rate / 10 * 2];
        long n = 0;
        for (int i = 0; i < seconds * 10; i++) {
            for (int j = 0; j < chunk.length; j += 2) {
                float s = (float) (amp * Math.sin(2 * Math.PI * hz * n / rate));
                chunk[j] = s; chunk[j + 1] = s; n++;
            }
            t.write(chunk, 0, chunk.length, AudioTrack.WRITE_BLOCKING);
        }
        t.stop(); t.release();
        System.out.println("トーン終了 " + hz + "Hz " + dbfs + "dBFS");
    }

    // トーンを鳴らしつつ、その AudioTrack のセッションに自分のエフェクトを付ける。
    // session 0 は「どのスレッドに載るか」を選べず、無音のスレッドに刺さることがある
    // (実測: io 85 が standby のまま frames だけ進み、入力は silent だった)。
    // トラックのセッションに付ければ、その音が必ず process() を通る。
    static int newSessionId() throws Exception {
        Class<?> as = Class.forName("android.media.AudioSystem");
        Method m = as.getDeclaredMethod("newAudioSessionId");
        m.setAccessible(true);
        return (Integer) m.invoke(null);
    }

    // order 0 = トラックを作ってからエフェクト、1 = エフェクトを作ってからトラック。
    // チェーンが後から足されたときにトラックの mainBuffer が張り替わるかは端末次第なので、
    // 両方試して差を見る。
    static void tonefx(int hz, int seconds, double dbfs, int gainMb, int order) throws Exception {
        if (dbfs > MAX_DBFS) {
            System.out.println("dBFS が " + MAX_DBFS + " を超えている。丸める (要求値=" + dbfs + ")");
            dbfs = MAX_DBFS;
        }
        if (dbfs < MIN_DBFS) dbfs = MIN_DBFS;
        if (gainMb > MAX_GAIN_MB) gainMb = MAX_GAIN_MB;
        if (gainMb < MIN_GAIN_MB) gainMb = MIN_GAIN_MB;
        final int rate = 48000;
        final double amp = Math.pow(10.0, dbfs / 20.0);
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        int buf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                                              AudioFormat.ENCODING_PCM_FLOAT) * 8;
        Constructor<AudioEffect> c = AudioEffect.class.getDeclaredConstructor(
                UUID.class, UUID.class, int.class, int.class);
        c.setAccessible(true);

        AudioTrack t;
        AudioEffect fx;
        int session;
        if (order == 1) {
            session = newSessionId();
            System.out.println("order=1 (エフェクトが先) session=" + session);
            fx = c.newInstance(effectTypeNull(), IMPL, 0, session);
            t = new AudioTrack.Builder().setAudioFormat(fmt).setAudioAttributes(attrs)
                    .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM)
                    .setSessionId(session).build();
        } else {
            t = new AudioTrack.Builder().setAudioFormat(fmt).setAudioAttributes(attrs)
                    .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM).build();
            session = t.getAudioSessionId();
            System.out.println("order=0 (トラックが先) session=" + session);
            fx = c.newInstance(effectTypeNull(), IMPL, 0, session);
        }
        t.setVolume(TRACK_VOLUME);
        System.out.println("生成した: " + fx.getDescriptor().name);
        System.out.println("setParameter=" + setParam(fx, intToLe(1), intToLe(gainMb))
                + " gain=" + gainMb + "mB");
        System.out.println("setEnabled=" + fx.setEnabled(true) + " enabled=" + fx.getEnabled());

        t.play();
        System.out.println("トーン開始 " + hz + "Hz " + dbfs + "dBFS vol=" + TRACK_VOLUME);
        float[] chunk = new float[rate / 10 * 2];
        long n = 0;
        for (int i = 0; i < seconds * 10; i++) {
            for (int j = 0; j < chunk.length; j += 2) {
                float s = (float) (amp * Math.sin(2 * Math.PI * hz * n / rate));
                chunk[j] = s; chunk[j + 1] = s; n++;
            }
            t.write(chunk, 0, chunk.length, AudioTrack.WRITE_BLOCKING);
        }
        t.stop(); t.release();
        fx.setEnabled(false); fx.release();
        System.out.println("終了");
    }

    static void query(String mac) throws Exception {
        boolean found = false;
        for (AudioEffect.Descriptor d : AudioEffect.queryEffects()) {
            if (d.name != null && d.name.contains("Codec Anchor")) {
                System.out.println("登録あり: " + d.name + " uuid=" + d.uuid + " type=" + d.type);
                found = true;
            }
        }
        if (!found) System.out.println("登録なし: Codec Anchor が queryEffects に出ない");
        // 型の互換性しか見ていないので「可否」の判定にはならないが、「登録されているか」には使える。
        System.out.println("isEffectSupportedForDevice=" + isSupportedForDevice(mac));
    }

    // Step 3 でカウンタが最後まで 0 だった場合にだけ使う。
    // descriptor は DEVICE / postprocess / session 0 の 3 経路共用なので、
    // .so を作り直さずに「.so 自体は動くか」を切り分けられる。
    static void session0(int gainMb) throws Exception {
        // AudioEffect(UUID type, UUID uuid, int priority, int session) も公開 jar に無い。
        Constructor<AudioEffect> c = AudioEffect.class.getDeclaredConstructor(
                UUID.class, UUID.class, int.class, int.class);
        c.setAccessible(true);
        AudioEffect fx = c.newInstance(effectTypeNull(), IMPL, 0, 0);
        if (gainMb > MAX_GAIN_MB) gainMb = MAX_GAIN_MB;
        if (gainMb < MIN_GAIN_MB) gainMb = MIN_GAIN_MB;
        System.out.println("setParameter=" + setParam(fx, intToLe(1), intToLe(gainMb)));
        System.out.println("setEnabled=" + fx.setEnabled(true));
        Thread.sleep(60000);
        fx.release();
        System.out.println("解放した");
    }

    // 生成が失敗したときの切り分け用。どの経路に入ったかを Java 側から確かめる。
    static void diag(String mac) throws Exception {
        Class<?> ada = Class.forName("android.media.AudioDeviceAttributes");
        System.out.println("--- AudioEffect の宣言済みコンストラクタ ---");
        for (Constructor<?> c : AudioEffect.class.getDeclaredConstructors()) {
            System.out.println("  " + c);
        }
        System.out.println("--- AudioDeviceAttributes ---");
        Object d = deviceAttrs(mac);
        System.out.println("  toString = " + d);
        for (String m : new String[] { "getInternalType", "getType", "getRole", "getAddress" }) {
            try {
                Method mm = ada.getDeclaredMethod(m);
                mm.setAccessible(true);
                System.out.println("  " + m + "() = " + mm.invoke(d));
            } catch (Exception e) {
                System.out.println("  " + m + "() -> " + e);
            }
        }
        System.out.println("--- AudioEffect のフィールド (device 関連) ---");
        for (Field f : AudioEffect.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase();
            if (n.contains("device") || n.contains("session")) {
                f.setAccessible(true);
                System.out.println("  " + f.getName() + " : " + f.getType().getSimpleName());
            }
        }
    }

    public static void main(String[] args) {
        int rc = 0;
        try {
            dispatch(args);
        } catch (Throwable t) {
            // app_process は main を抜けた例外を表に出さないことがある (実測: 出力なしで
            // "Killed" だけ残る)。原因を握りつぶさないよう自分で出す。
            t.printStackTrace(System.out);
            rc = 1;
        }
        // AudioTrack / AudioEffect が非デーモンスレッドを残すので、main から戻っても
        // プロセスは終わらない。切り分けを繰り返すたびに溜まるので明示的に落とす。
        System.exit(rc);
    }

    static void dispatch(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("使い方:");
            System.out.println("  hold <MAC> <gain_mB> <秒数>");
            System.out.println("  tone <Hz> <秒数> <dBFS>");
            System.out.println("  query <MAC>");
            System.out.println("  session0 <gain_mB>");
            return;
        }
        switch (args[0]) {
            case "hold":
                hold(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                break;
            case "tone":
                tone(Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                     Double.parseDouble(args[3]));
                break;
            case "query":
                query(args[1]);
                break;
            case "session0":
                session0(Integer.parseInt(args[1]));
                break;
            case "diag":
                diag(args[1]);
                break;
            case "tonefx":
                tonefx(Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                       Double.parseDouble(args[3]), Integer.parseInt(args[4]),
                       args.length > 5 ? Integer.parseInt(args[5]) : 0);
                break;
            default:
                System.out.println("不明なサブコマンド: " + args[0]);
        }
    }
}
