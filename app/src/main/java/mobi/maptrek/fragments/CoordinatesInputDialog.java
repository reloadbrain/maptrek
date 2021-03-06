package mobi.maptrek.fragments;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

import mobi.maptrek.R;
import mobi.maptrek.util.CoordinatesParser;

public class CoordinatesInputDialog extends DialogFragment {
    private int mColorTextPrimary;
    private int mColorDarkBlue;
    private int mColorRed;
    private String mLineSeparator = System.getProperty("line.separator");

    private CoordinatesInputDialogCallback mCallback;
    private AlertDialog mDialog;

    public interface CoordinatesInputDialogCallback {
        void onTextInputPositiveClick(String id, String inputText);

        void onTextInputNegativeClick(String id);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mColorTextPrimary = context.getColor(R.color.textColorPrimary);
        mColorDarkBlue = context.getColor(R.color.darkBlue);
        mColorRed = context.getColor(R.color.red);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Bundle args = getArguments();
        String title = args.getString("title", "");
        final String id = args.getString("id", null);

        final Activity activity = getActivity();

        @SuppressLint("InflateParams")
        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_coordinates_input, null);
        final EditText textEdit = (EditText) dialogView.findViewById(R.id.coordinatesEdit);

        textEdit.requestFocus();

        textEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 0)
                    return;
                String[] lines = s.toString().split(mLineSeparator);
                int offset = 0;
                ForegroundColorSpan[] spans = s.getSpans(0, s.length(), ForegroundColorSpan.class);
                if (spans != null && spans.length > 0) {
                    Log.e("CID", "L: " + spans.length);
                    for (ForegroundColorSpan span : spans)
                        s.removeSpan(span);
                }
                for (String line : lines) {
                    try {
                        CoordinatesParser.Result result = CoordinatesParser.parseWithResult(line);
                        s.setSpan(
                                new ForegroundColorSpan(mColorTextPrimary),
                                offset,
                                s.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        for (CoordinatesParser.Token token : result.tokens) {
                            s.setSpan(
                                    new ForegroundColorSpan(mColorDarkBlue),
                                    offset + token.i,
                                    offset + token.i + token.l,
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                    } catch (IllegalArgumentException e) {
                        s.setSpan(
                                new ForegroundColorSpan(mColorRed),
                                offset,
                                s.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    offset += line.length() + mLineSeparator.length();
                }
            }
        });

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(activity);
        dialogBuilder.setTitle(title);
        dialogBuilder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mCallback.onTextInputPositiveClick(id, textEdit.getText().toString());
            }
        });
        dialogBuilder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mCallback.onTextInputNegativeClick(id);
            }
        });
        dialogBuilder.setNeutralButton(R.string.explain, null);
        dialogBuilder.setView(dialogView);
        mDialog = dialogBuilder.create();
        mDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        // Workaround to prevent dialog dismissing
        mDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                mDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                        builder.setMessage(R.string.msgCoordinatesInputExplanation);
                        builder.setPositiveButton(R.string.ok, null);
                        AlertDialog dialog = builder.create();
                        dialog.show();
                    }
                });
            }
        });
        return mDialog;
    }

    public void setCallback(CoordinatesInputDialogCallback callback) {
        mCallback = callback;
    }

    public static class Builder {
        private String mTitle;
        private String mId;
        private CoordinatesInputDialogCallback mCallbacks;

        public Builder setTitle(String title) {
            mTitle = title;
            return this;
        }

        public Builder setId(String id) {
            mId = id;
            return this;
        }

        public Builder setCallbacks(CoordinatesInputDialogCallback callbacks) {
            mCallbacks = callbacks;
            return this;
        }

        public CoordinatesInputDialog create() {
            CoordinatesInputDialog dialogFragment = new CoordinatesInputDialog();
            Bundle args = new Bundle();

            if (mTitle != null)
                args.putString("title", mTitle);
            if (mId != null)
                args.putString("id", mId);
            dialogFragment.setCallback(mCallbacks);
            dialogFragment.setArguments(args);
            return dialogFragment;
        }
    }
}