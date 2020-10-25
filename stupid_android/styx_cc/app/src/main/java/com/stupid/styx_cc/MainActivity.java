package com.stupid.styx_cc;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {

    private final static int PERM_REQUEST_CODE_ = 1;
    private final static int MIN_IMAGE_SIZE_ = 32;
    private final static int[] STYLES = new int[]{
            R.drawable.style1,
            R.drawable.style2,
            R.drawable.style3,
            R.drawable.style4,
            R.drawable.style5,
            R.drawable.style_1,
            R.drawable.style_2,
            R.drawable.style_3,
            R.drawable.style_4,
            R.drawable.style_5,
            R.drawable.style_6,
            R.drawable.style_7,
            R.drawable.style_8,
            R.drawable.style_9,
            R.drawable.style_10,
            R.drawable.style_11,
            R.drawable.style_12,
            R.drawable.style_13,
    };

    private ModelInfo big_model_info_ = new ModelInfo("big4321.tflite",
            new ModelConfig(25000, 64, 256),
            new ModelConfig( 85000, 128, 512));

    ModelInfo getModelInfo() {
        return big_model_info_;
    }

    static {
        System.loadLibrary("register-svd");
    }

    private ImageState image_state_ = ImageState.STYLE;
    private ModelState model_state_ = ModelState.UNINITIALIZED;

    private TextView tv_;
    private ImageView image_;
    private SeekBar stylebar_;
    private ImageView thumbnail_;

    private File getModelFile(ModelInfo info) {
        Log.i("main", "getModelFile");
        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File model_file = new File(downloads, info.name);
        if (!model_file.exists()) {
            throw new RuntimeException("No such file: " + model_file.getAbsolutePath());
        }
        return model_file;
    }

    private void InitModel(ModelInfo info) {
        Log.i("main", "InitModel");
        if (model_state_ != ModelState.UNINITIALIZED) {
            // The model has already been initialized.
            return;
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Storage permission denied.");
            return;
        }
        try {
            File modelPath = getModelFile(info);
            String init_message = prepareInterpreter(modelPath.getAbsolutePath());
            if (!init_message.isEmpty()) {
                setStatus("Init failed: " + init_message);
                return;
            }
        } catch (RuntimeException e) {
            setStatus(e.getMessage());
            return;
        }
        setModelState(ModelState.IDLE);
        setStatus("Waiting...");
    }

    private Tensor LoadResourceImageAsTensor(int max_image_size,
                                             int resource_index) {
        Log.i("main", "LoadResourceImageAsTensor");
        Timer timer = new Timer();
        Bitmap bitmap = BitmapFactory.decodeResource(getResources(), resource_index);

        int max_size = Math.max(bitmap.getWidth(), bitmap.getHeight());
        int new_size = Math.max(Math.min(max_size, max_image_size), MIN_IMAGE_SIZE_);

        double scale_factor = (double) new_size / (double) max_size;

        int new_height = (int)(bitmap.getHeight() * scale_factor);
        int new_width = (int)(bitmap.getWidth() * scale_factor);

        bitmap = Bitmap.createScaledBitmap(bitmap, new_width, new_height, false);

        int size = bitmap.getHeight() * bitmap.getWidth() * 3;
        Tensor tensor = new Tensor();
        tensor.shape = new int[]{1, bitmap.getWidth(), bitmap.getHeight(), 3};
        tensor.data = new float[size];
        int index = 0;
        for (int x = 0; x < bitmap.getWidth(); ++x) {
            for (int y = 0; y < bitmap.getHeight(); ++y) {
                int pixel = bitmap.getPixel(x, y);

                int red = (pixel >> 16) & 0xFF;
                int green = (pixel >> 8) & 0xFF;
                int blue = pixel & 0xFF;

                assert red >= 0 && red <= 255;
                assert green >= 0 && green <= 255;
                assert blue >= 0 && blue <= 255;

                tensor.data[index] = red;
                index++;
                tensor.data[index] = green;
                index++;
                tensor.data[index] = blue;
                index++;
            }
        }
        Log.i("LoadResourceImage", "Took " + timer.getTimeDelta() + "ms");
        return tensor;
    }

    private int toColorValue(float value) {
        int int_value = (int) (value);
        if (int_value < 0) {
            return 0;
        }
        if (int_value > 255) {
            return 255;
        }
        return int_value;
    }

    private Bitmap TensorToBitmap(Tensor tensor) {
        Timer timer = new Timer();
        int width = tensor.shape[1];
        int height = tensor.shape[2];

        Bitmap.Config conf = Bitmap.Config.ARGB_8888;
        Bitmap image = Bitmap.createBitmap(width, height, conf);

        int index = 0;
        for (int x = 0; x < width; ++x) {
            for (int y = 0; y < height; ++y) {
                int red = toColorValue(tensor.data[index]);
                index++;
                int green = toColorValue(tensor.data[index]);
                index++;
                int blue = toColorValue(tensor.data[index]);
                index++;
                int color = Color.rgb(
                        red,
                        green,
                        blue
                );
                image.setPixel(x, y, color);
            }
        }


        int new_height = image_.getDrawable().getIntrinsicHeight();
        int new_width = image_.getDrawable().getIntrinsicWidth();
        image = Bitmap.createScaledBitmap(image, new_width, new_height, true);
        Log.i("TensorToBitmap", "Took " + timer.getTimeDelta() + "ms");
        return image;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        Log.i("main", "onRequestPermissionsResult");
        assert requestCode == PERM_REQUEST_CODE_;
        assert permissions.length == 1;
        assert grantResults.length == 1;
        assert permissions[0].equals(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setStatus("Permission granted.");
        } else {
            setStatus("Permission denied.");
        }
    }

    private String runModel(ModelInfo info, boolean preview) {
        Log.i("main", "runModel");
        ModelConfig config = info.getModelConfig(preview);
        final Timer timer = new Timer();
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return "Permission denied.";
        }
        Tensor content = LoadResourceImageAsTensor(
                config.max_image_size,
                R.drawable.gilbert);
        Tensor style = LoadResourceImageAsTensor(
                config.max_image_size,
                STYLES[stylebar_.getProgress()]);
        final Tensor result = new Tensor();
        String error = runStyleTransfer(config.svd_rank, content, style, result);
        if (!error.isEmpty()) {
            return "Transfer failed: " + error;
        }
        setImage(TensorToBitmap(result));
        setThumbnail(STYLES[stylebar_.getProgress()]);
        if (preview) {
            setStatus("Preview (" + (timer.getTimeDelta()) + " milliseconds)");
        } else {
            setStatus("Full (" + (timer.getTimeDelta()) + " milliseconds)");
        }
        return "";
    }

    void startModelThread(final ModelInfo info, final boolean preview) {
        new Thread(new Runnable() {
            public void run() {
                String error = runModel(info, preview);
                image_state_ = ImageState.PREVIEW;
                if (!error.isEmpty()) {
                    setStatus(error);
                } else if (preview) {
                    image_state_ = ImageState.PREVIEW;
                } else {
                    image_state_ = ImageState.FULL;
                }
                setModelState(ModelState.IDLE);
            }
        }).start();
    }

    private void setStatus(final String status_message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_.setText(status_message);
            }
        });
    }

    private void setImage(final Bitmap bitmap) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                image_.setImageBitmap(bitmap);
            }
        });
    }

    private void setImage(final int resource) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                image_.setImageResource(resource);
            }
        });
    }

    private void setThumbnail(final int resource) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                thumbnail_.setImageResource(resource);
            }
        });
    }

    void startProgressThread(final ModelInfo info, final boolean preview){
        new Thread(new Runnable() {
            final Timer timer = new Timer();
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                    if (model_state_ != ModelState.RUNNING) {
                        return;
                    }
                    double run_time = timer.getTimeDelta();
                    double total_run_time = info.getModelConfig(preview).runtime_ins_ms;
                    final int progress = Math.min((int) ((run_time / total_run_time) * 100.0), 100);
                    if (preview) {
                        setStatus("Creating preview (" + progress + "%)");
                    } else {
                        setStatus("Creating image (" + progress + "%)");
                    }
                    if (progress >= 100) {
                        return;
                    }
                }
            }
        }).start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        stylebar_ = findViewById(R.id.styleBar);
        stylebar_.setMin(0);
        stylebar_.setMax(STYLES.length - 1);
        stylebar_.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        Log.i("Stylebar", "Style: " + progress);
                        setImage(STYLES[progress]);
                        setThumbnail(R.drawable.gilbert);
                        image_state_ = ImageState.STYLE;
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {

                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {

                    }
                }
        );


        image_ = findViewById(R.id.styleView);
        thumbnail_ = findViewById(R.id.Thumbnail);
        tv_ = findViewById(R.id.textView);

        image_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModelInfo info = getModelInfo();
                if (model_state_ == ModelState.UNINITIALIZED) {
                    InitModel(info);
                }
                if (model_state_ != ModelState.IDLE) {
                    return;
                }
                setModelState(ModelState.RUNNING);
                final boolean preview = image_state_ == ImageState.STYLE;
                startModelThread(info, preview);
                startProgressThread(info, preview);
            }
        });

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Waiting for permissions");
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERM_REQUEST_CODE_);
        } else {
            setStatus("Good to go!");
        }
    }

    private native String prepareInterpreter(String model_path);

    private native String runStyleTransfer(int svd_rank,
                                           Tensor content,
                                           Tensor style,
                                           Tensor result);

    enum ImageState {
        STYLE,
        PREVIEW,
        FULL,
    }

    enum ModelState {
        UNINITIALIZED,
        RUNNING,
        IDLE,
    }

    private void setStyleBarEnabled(final boolean enabled) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stylebar_.setEnabled(enabled);
            }
        });
    }

    private void setModelState(ModelState state) {
        if (state == ModelState.RUNNING) {
            setStyleBarEnabled(false);
        } else {
            setStyleBarEnabled(true);
        }
        model_state_ = state;
    }

    private class Timer {
        private final long time_;

        public Timer() {
            time_ = System.currentTimeMillis();
        }

        public long getTimeDelta() {
            return System.currentTimeMillis() - time_;
        }
    }

    private class Tensor {
        int[] shape;
        float[] data;

        @Override
        public String toString() {
            String output = "";
            output += "shape: " + Arrays.toString(shape);
            output += " data: " + Arrays.toString(data);
            return output;
        }
    }

    private class ModelConfig {
        ModelConfig(
                int runtime_ins_ms,
                int svd_rank,
                int max_image_size) {
            this.runtime_ins_ms = runtime_ins_ms;
            this.svd_rank = svd_rank;
            this.max_image_size = max_image_size;
        }

        public int runtime_ins_ms;
        public int svd_rank;
        public int max_image_size;
    }

    private class ModelInfo {
        public ModelInfo(String name, ModelConfig preview, ModelConfig full) {
            this.name = name;
            this.preview_ = preview;
            this.full_ = full;
        }

        ModelConfig getModelConfig(boolean preview) {
            return (preview)? preview_: full_;
        }

        public String name;
        public ModelConfig preview_;
        public ModelConfig full_;
    };
}
