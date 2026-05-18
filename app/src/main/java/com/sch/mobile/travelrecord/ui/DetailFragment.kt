package com.sch.mobile.travelrecord.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sch.mobile.travelrecord.R

class DetailFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_detail, container, false)

    companion object {
        @JvmStatic
        fun newInstance(tripId: Long): DetailFragment = DetailFragment()
    }
}
