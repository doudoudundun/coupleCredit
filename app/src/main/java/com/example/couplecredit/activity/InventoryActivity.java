package com.example.couplecredit.activity;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.couplecredit.R;
import com.example.couplecredit.fragment.InventoryFragment;

/**
 * 存货清单Activity
 */
public class InventoryActivity extends AppCompatActivity {

    private InventoryFragment inventoryFragment;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inventory);

        FragmentManager fragmentManager = getSupportFragmentManager();

        if (savedInstanceState == null) {
            inventoryFragment = new InventoryFragment();
            FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.add(R.id.inventory_container, inventoryFragment, "inventory");
            transaction.commit();
        } else {
            inventoryFragment = (InventoryFragment) fragmentManager.findFragmentByTag("inventory");
        }
    }
}