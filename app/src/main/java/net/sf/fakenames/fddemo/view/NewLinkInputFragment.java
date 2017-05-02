/*
 * Copyright © 2017 Alexander Rvachev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.fakenames.fddemo.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.design.widget.TextInputEditText;
import android.support.design.widget.TextInputLayout;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;

import net.sf.fakenames.fddemo.R;

import butterknife.BindView;
import butterknife.ButterKnife;

public final class NewLinkInputFragment extends DialogFragment implements DialogInterface.OnClickListener {
    private static final String ARG_TITLE = "fileTypeName";
    private static final String ARG_TYPE = "fileTypeId";

    public NewLinkInputFragment() {}

    @SuppressLint("ValidFragment")
    public NewLinkInputFragment(String fileTypeTitle, int type) {
        final Bundle bundle = new Bundle();
        bundle.putString(ARG_TITLE, fileTypeTitle);
        bundle.putInt(ARG_TYPE, type);
        setArguments(bundle);
    }

    private String fileTypeTitle;
    private int type;

    @BindView(R.id.dlg_sym_input)
    TextInputEditText editText;

    @BindView(R.id.dlg_sym_input_layout)
    TextInputLayout editLayout;

    @BindView(R.id.dlg_sym_targ_input)
    TextInputEditText editTargetText;

    @BindView(R.id.dlg_sym_targ_input_layout)
    TextInputLayout editTargetLayout;

    @Override
    @SuppressWarnings("deprecation")
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        final Bundle bundle = getArguments();
        fileTypeTitle = bundle.getString(ARG_TITLE);
        type = bundle.getInt(ARG_TYPE);
    }

    @Override
    @SuppressLint("InflateParams")
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final View view = LayoutInflater.from(getActivity()).inflate(R.layout.link_input, null, false);

        ButterKnife.bind(this, view);

        editLayout.setHint(getString(R.string.enter_name, fileTypeTitle));
        editTargetLayout.setHint(getString(R.string.enter_target, fileTypeTitle));

        return new AlertDialog.Builder(getActivity())
                .setCancelable(true)
                .setView(view)
                .setPositiveButton(android.R.string.ok, this)
                .create();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        final String name = editText.getText().toString();
        final String target = editTargetText.getText().toString();

        ((LinkNameReceiver) getActivity()).onLinkParamsChosen(name, target, type);
    }

    public interface LinkNameReceiver {
        void onLinkParamsChosen(String name, String target, int type);
    }
}